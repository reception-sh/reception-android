package sh.reception

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.FirebaseBridge
import sh.reception.client.NotificationRouting
import sh.reception.client.NotificationSmallIcon
import sh.reception.client.PushMessageIds
import sh.reception.client.PushNotifications
import sh.reception.client.Storage
import sh.reception.client.hasConversation
import java.util.UUID

/** Push receipt, notification taps and token registration behind the facade (SPEC.md, Push notifications). Main thread only. */
internal object ReceptionPush {
    /** The app's settings set right after configure apply before a tap that launched the app opens the chat. */
    private const val DRAIN_DELAY_MILLIS = 500L

    private val routing = NotificationRouting()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var registration: Job? = null
    private var drain: Job? = null

    /** False while a Flutter or React Native app handles notifications itself: taps only bring the app forward. */
    private var routesTaps = true

    /** `Reception.handlePushMessage` with a raw FCM data map (a broadcast's extras), which still carries the message ID. */
    fun receive(context: Context, data: Map<String, String>): Boolean =
        receive(context, data, data[PushMessageIds.DATA_KEY])

    /**
     * `Reception.handlePushMessage`, from any thread: recognizes the payload here, handles it on the main thread.
     * [messageId] is the FCM message ID (`RemoteMessage.messageId`), which Firebase keeps out of the data map.
     */
    fun receive(context: Context, data: Map<String, String>, messageId: String?): Boolean {
        if (PushNotifications.payload(data) == null) return false
        val application = context.applicationContext
        val payload = data.toMap()
        if (messageId.isNullOrEmpty()) return true.also { MainThread.run { handleMessage(application, payload) } }
        // One storage thread in submission order, so a repeat always sees the first delivery's record.
        Storage.launch {
            val first = PushMessageIds.record(application, messageId)
            MainThread.run {
                if (first) handleMessage(application, payload) else Log.debug { "Push message ignored, handled already" }
            }
        }
        return true
    }

    /** `Reception.handlePushNotification`, from any thread. */
    fun route(data: Map<String, String>, openChat: Boolean): Boolean {
        if (PushNotifications.payload(data) == null) {
            Log.debug { "Push payload ignored, no conversation id" }
            return false
        }
        val payload = data.toMap()
        MainThread.run { handleNotification(payload, openChat, PendingChatOpen.Source.HOST) }
        return true
    }

    /** [data] is a Reception payload. */
    private fun handleMessage(context: Context, data: Map<String, String>) {
        val payload = PushNotifications.payload(data) ?: return
        Log.debug { "Push payload recognized, conversation ${payload.conversationId}" }
        val key = UUID.randomUUID().toString()
        routing.action(key, openChat = false)
        received(payload.conversationId)
        if (ReceptionState.isChatOpen.value) return Log.debug { "Notification not shown, chat open" }
        scope.launch {
            // Before configure nothing has read the app's declared languages, which name the channel.
            AppLocale.prepared(context)
            if (ReceptionState.isChatOpen.value) return@launch Log.debug { "Notification not shown, chat open" }
            val badges = ReceptionState.updatesAppBadge
            val number = PushNotifications.number(payload, badges)
            val accent = ReceptionAppearance.resolved.accent.light?.toArgb()
            val icon = NotificationSmallIcon.resolved(context)
            PushNotifications.post(context, payload, key, badges, number, accent, icon)
        }
    }

    /** `Reception.handlePushNotification`, the iOS routing call; it never posts a notification. */
    private fun handleNotification(data: Map<String, String>, openChat: Boolean, source: PendingChatOpen.Source): Boolean {
        val id = data[PushNotifications.PAYLOAD_KEY]?.takeIf { it.isNotEmpty() }
            ?: return false.also { Log.debug { "Push payload ignored, no conversation id" } }
        Log.debug { "Push payload recognized, conversation $id" }
        received(id)
        if (openChat) requestOpen(source)
        return true
    }

    /** The service wrote to this device, so the chat exists even if nobody here has written yet. */
    private fun received(conversationId: String) {
        val session = ReceptionState.session ?: return Log.debug { "Push payload for $conversationId before configure" }
        session.afterPrepared { if (session === ReceptionState.session) session.store.hasConversation = true }
        ReceptionUnread.refresh()
    }

    /** Opens the chat over the resumed activity, or over the next one when the app is still starting. */
    private fun requestOpen(source: PendingChatOpen.Source) {
        if (ReceptionState.session == null) return Log.error { "openChat called before configure, ignored" }
        if (ApplicationLifecycle.currentActivity == null) return PendingChatOpen.request(source)
        PendingChatOpen.open(source)
    }

    /** `Reception.registerForPushIfAuthorized()`: at configure, every foreground, after logout and deletion. */
    fun registerIfAuthorized() {
        val session = ReceptionState.session
            ?: return Log.error { "registerForPushIfAuthorized called before configure, ignored" }
        val context = session.store.context
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return Log.debug { "Push registration skipped, notifications not allowed" }
        }
        if (registration?.isActive == true) return
        registration = scope.launch {
            val token = FirebaseBridge.token(context) ?: return@launch
            // Every foreground asks again; an unchanged token needs no device update.
            if (ReceptionState.session?.pending?.pushToken == token) return@launch
            ReceptionAccount.setPushToken(token)
        }
    }

    /** `Reception.setNotificationRoutingEnabled`: off discards the pending tap and any chat a tap was about to open. */
    fun setRoutingEnabled(enabled: Boolean) {
        routesTaps = enabled
        if (enabled) return
        drain?.cancel()
        drain = null
        routing.discardPendingTap()
        PendingChatOpen.routingTurnedOff()
    }

    /** A configure: a tap that launched the app before it opens the chat once, shortly after. */
    fun configured() {
        drain?.cancel()
        if (!routesTaps || !routing.hasPendingTap) return
        drain = scope.launch {
            delay(DRAIN_DELAY_MILLIS)
            val conversationId = routing.drainPendingTap() ?: return@launch
            handleNotification(mapOf(PushNotifications.PAYLOAD_KEY to conversationId), openChat = true, PendingChatOpen.Source.NOTIFICATION_TAP)
        }
    }

    /** A tap on one of the SDK's notifications, from [sh.reception.ui.ReceptionNotificationActivity]. */
    fun notificationTapped(key: String, conversationId: String) {
        if (!routesTaps) return Log.debug { "Notification tap not routed, the app handles notifications" }
        if (ReceptionState.session == null) {
            routing.queueTap(key, conversationId)
            return Log.debug { "Notification tap kept until configure" }
        }
        when (routing.action(key, openChat = true)) {
            NotificationRouting.Action.RECEIVE_AND_OPEN -> {
                received(conversationId)
                PendingChatOpen.request(PendingChatOpen.Source.NOTIFICATION_TAP)
            }
            NotificationRouting.Action.OPEN -> PendingChatOpen.request(PendingChatOpen.Source.NOTIFICATION_TAP)
            NotificationRouting.Action.RECEIVE, NotificationRouting.Action.IGNORE -> Log.debug { "Notification tap ignored, handled already" }
        }
    }

    /** Unread reached [count]; with `updatesAppBadge`, read replies leave the notification shade. */
    fun unreadChanged(count: Int) {
        if (count != 0 || !ReceptionState.updatesAppBadge) return
        val context = ReceptionState.session?.store?.context ?: return
        PushNotifications.cancelAll(context)
    }
}
