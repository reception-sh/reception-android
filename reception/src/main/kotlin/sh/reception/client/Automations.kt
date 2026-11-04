package sh.reception.client

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import sh.reception.Log
import sh.reception.ReceptionState
import sh.reception.ReceptionUnread

/**
 * Sends tracked events that a dashboard automation listens for, and opens the chat when the service sent its message.
 * Every failure ends quietly: an automation is optional and must never disturb the host app. Main thread only.
 * [SubscriptionEvents] reports store subscription changes through it.
 */
internal class Automations(
    private val presenter: AutomationPresenter = AutomationPresenter.Chat,
    private val paidAccess: PaidAccess? = null,
    val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Listened(val appId: String, val events: Set<String>, val until: Long)

    @Serializable
    private class Sent(val sent: Boolean, val scheduled: Boolean? = null, val openChat: Boolean = false)

    @Serializable
    private class EventInput(val event: String)

    @Serializable
    private class ListenedResponse(val events: List<String>)

    /**
     * What became of an event: no enabled automation listens for it, send it again later, its message was just sent,
     * or it was handled otherwise; both with whether the service says a chat-opening automation reached this device.
     */
    sealed interface Delivery {
        data object Unheard : Delivery
        data object Retry : Delivery
        data class Received(val openChat: Boolean) : Delivery
        data class Handled(val openChat: Boolean) : Delivery
    }

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listened: Listened? = null
    private var listing: Pair<String, Deferred<Listened>>? = null
    private val running = mutableSetOf<String>()
    private var openedChatAt: Long? = null

    fun track(event: String, opensChat: Boolean, session: DeviceSession) {
        val start = clock()
        val presentation = ReceptionState.presentationRevision
        scope.launch {
            val delivery = deliver(event, session)
            if (session.halt != null) return@launch
            when {
                delivery == Delivery.Received(openChat = true) -> openChatWhenSettled(start, presentation, arriving = true)
                // The service decides: the chat opens again only for people an enabled automation that opens it
                // reached, so nobody it excludes, delays or reaches with a badge or push sees an empty chat.
                delivery == Delivery.Handled(openChat = true) && opensChat ->
                    openChatWhenSettled(start, presentation, arriving = false)
            }
        }
    }

    /** Sends [event] when an automation listens for it. Retry only when it should be sent again later. */
    suspend fun deliver(event: String, session: DeviceSession): Delivery {
        if (session.halt != null) return Delivery.Retry
        if (!listens(setOf(event), session)) return Delivery.Unheard
        if (!running.add(event)) return Delivery.Retry
        try {
            val result = send(event, session) ?: return Delivery.Retry
            if (session !== ReceptionState.session) return Delivery.Handled(openChat = false)
            if (result.sent) {
                Log.debug { "Automation message received for $event" }
                session.store.hasStartedChat = true
                ReceptionUnread.refresh()
                return Delivery.Received(result.openChat)
            }
            // A message that waits for its time arrives later, so the chat watches for it from now on, as after a send.
            if (result.scheduled == true) {
                Log.debug { "Automation message scheduled for $event" }
                session.store.hasStartedChat = true
            }
            return Delivery.Handled(result.openChat)
        } finally {
            running.remove(event)
        }
    }

    /**
     * Opens the chat as soon as the app is active and no activity is still starting or closing, if that comes within
     * the window after the event; later, it would surprise the user. The chat stays closed if the person logged out
     * meanwhile or the chat is already open.
     */
    suspend fun openChatWhenSettled(start: Long, presentation: Int, arriving: Boolean) {
        while (clock() - start < OPEN_WINDOW_MILLIS) {
            if (presentation != ReceptionState.presentationRevision || ReceptionState.isChatOpen.value) return
            if (presenter.isSettled) {
                if (arriving) openedChatAt = clock()
                if (presenter.present()) return
            }
            delay(SETTLE_POLL_MILLIS)
        }
        Log.debug { "Chat not opened, the app or its screens did not settle in time" }
    }

    /** Whether an automation opened the chat a moment ago, so its message can arrive like a reply. Answers once. */
    fun takeOpenedChat(): Boolean {
        val at = openedChatAt ?: return false
        openedChatAt = null
        return clock() - at < OPEN_WINDOW_MILLIS
    }

    /** Whether an enabled automation listens for one of [events]. Other events never leave the device. */
    suspend fun listens(events: Set<String>, session: DeviceSession): Boolean {
        val appId = session.api.appId
        listened?.let { if (it.appId == appId && clock() < it.until) return it.events.any(events::contains) }
        // Events often arrive together, like a closed paywall and the purchase it ended; one request answers them all.
        val task = listing?.takeIf { it.first == appId }?.second
            ?: scope.async { fetchListened(appId, session.api) }.also { listing = appId to it }
        val result = task.await()
        if (listing?.second === task) listing = null
        listened = result
        return result.events.any(events::contains)
    }

    private suspend fun fetchListened(appId: String, api: ReceptionAPI): Listened = try {
        val response = api.request<ListenedResponse>("automations")
        Listened(appId, response.events.toSet(), clock() + LISTENED_MILLIS)
    } catch (_: ReceptionAPIError) {
        Log.debug { "Automations unavailable, events are not sent for a minute" }
        Listened(appId, emptySet(), clock() + UNAVAILABLE_MILLIS)
    }

    private suspend fun send(event: String, session: DeviceSession): Sent? = try {
        withContext(RequestContext(automation = true)) {
            // The service decides with current paid access, metadata and paywalls, so a registered device syncs first.
            paidAccess?.refreshNow(session.store.context)
            session.prepared()
            if (session.isRegistered) session.update()
            session.api.request<Sent>("automations/events", "POST", authenticated = true,
                body = ReceptionAPI.encode(EventInput(event)))
        }
    } catch (_: ReceptionAPIError) {
        Log.debug { "Automation event not delivered" }
        null
    }

    companion object {
        /** The chat opens only this soon after `track`, so a slow response never surprises the user later. */
        const val OPEN_WINDOW_MILLIS: Long = 5_000
        private const val SETTLE_POLL_MILLIS = 50L
        private const val LISTENED_MILLIS = 300_000L
        private const val UNAVAILABLE_MILLIS = 60_000L
        private val eventName = Regex("^[A-Za-z0-9_.-]{1,64}$")

        fun accepts(event: String): Boolean = eventName.matches(event)
    }
}
