package sh.reception

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.BadParcelableException
import android.os.Bundle
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.OnNewIntentProvider
import androidx.core.util.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.FirebaseBridge
import sh.reception.client.NotificationRouting
import sh.reception.client.PushNotifications
import sh.reception.client.hasConversation
import sh.reception.ui.ChatPresenter
import java.util.UUID
import java.util.WeakHashMap

/** Push receipt, notification taps and token registration behind the facade (SPEC.md, Push notifications). Main thread only. */
internal object ReceptionPush {
    /** The app's settings set right after configure apply before a tap that launched the app opens the chat. */
    private const val DRAIN_DELAY_MILLIS = 500L

    var smallIcon: Int? = null
    private val routing = NotificationRouting()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var registration: Job? = null
    private var drain: Job? = null
    private var opensWhenResumed = false
    private val newIntentListeners = WeakHashMap<Activity, Consumer<Intent>>()

    /** `Reception.handlePushMessage`: [data] is a Reception payload. */
    fun handleMessage(context: Context, data: Map<String, String>) {
        val payload = PushNotifications.payload(data) ?: return
        Log.debug { "Push payload recognized, conversation ${payload.conversationId}" }
        val key = UUID.randomUUID().toString()
        routing.action(key, openChat = false)
        received(payload.conversationId)
        if (ReceptionState.isChatOpen.value) return Log.debug { "Notification not shown, chat open" }
        val number = PushNotifications.number(payload, ReceptionState.updatesAppBadge)
        val accent = ReceptionAppearance.resolved.accent.light?.toArgb()
        PushNotifications.post(context, payload, key, number, accent, smallIcon ?: R.drawable.reception_symbol_notifications)
    }

    /** `Reception.handlePushNotification`, the iOS routing call; it never posts a notification. */
    fun handleNotification(data: Map<String, String>, openChat: Boolean): Boolean {
        val id = data[PushNotifications.PAYLOAD_KEY]?.takeIf { it.isNotEmpty() }
            ?: return false.also { Log.debug { "Push payload ignored, no conversation id" } }
        Log.debug { "Push payload recognized, conversation $id" }
        received(id)
        if (openChat) requestOpen()
        return true
    }

    /** The service wrote to this device, so the chat exists even if nobody here has written yet. */
    private fun received(conversationId: String) {
        val session = ReceptionState.session ?: return Log.debug { "Push payload for $conversationId before configure" }
        session.afterPrepared { if (session === ReceptionState.session) session.store.hasConversation = true }
        ReceptionUnread.refresh()
    }

    /** Opens the chat over the resumed activity, or over the next one when the app is still starting. */
    private fun requestOpen() {
        if (ReceptionState.session == null) return Log.error { "openChat called before configure, ignored" }
        if (ApplicationLifecycle.currentActivity == null) {
            opensWhenResumed = true
            return Log.debug { "Chat opens once an activity is resumed" }
        }
        ChatPresenter.open()
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

    /** A configure: a tap that launched the app before it opens the chat once, shortly after. */
    fun configured() {
        drain?.cancel()
        if (!routing.hasPendingTap) return
        drain = scope.launch {
            delay(DRAIN_DELAY_MILLIS)
            val conversationId = routing.drainPendingTap() ?: return@launch
            handleNotification(mapOf(PushNotifications.PAYLOAD_KEY to conversationId), openChat = true)
        }
    }

    fun activityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // A recreated activity keeps the intent of a tap that was handled already.
        if (savedInstanceState == null) read(activity.intent)
        val provider = activity as? OnNewIntentProvider ?: return
        val listener = Consumer<Intent> { read(it) }
        newIntentListeners[activity] = listener
        provider.addOnNewIntentListener(listener)
    }

    fun activityResumed(activity: Activity) {
        // Activities that are not ComponentActivity see a tap on a running app only if they call setIntent.
        read(activity.intent)
        if (!opensWhenResumed) return
        opensWhenResumed = false
        // After the activity's own onResume, so the sheet starts over a settled screen.
        activity.window?.decorView?.post { if (ReceptionState.session != null) ChatPresenter.open() }
    }

    fun activityDestroyed(activity: Activity) {
        val listener = newIntentListeners.remove(activity) ?: return
        (activity as? OnNewIntentProvider)?.removeOnNewIntentListener(listener)
    }

    private fun read(intent: Intent?) {
        intent ?: return
        val conversationId: String
        val key: String
        try {
            conversationId = intent.getStringExtra(PushNotifications.EXTRA_CONVERSATION)?.takeIf { it.isNotEmpty() } ?: return
            key = intent.getStringExtra(PushNotifications.EXTRA_NOTIFICATION)?.takeIf { it.isNotEmpty() } ?: conversationId
        } catch (_: BadParcelableException) {
            return
        }
        intent.removeExtra(PushNotifications.EXTRA_CONVERSATION)
        intent.removeExtra(PushNotifications.EXTRA_NOTIFICATION)
        // Reopening from Recents replays the original launch intent.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (ReceptionState.session == null) {
            routing.queueTap(key, conversationId)
            return Log.debug { "Notification tap kept until configure" }
        }
        when (routing.action(key, openChat = true)) {
            NotificationRouting.Action.RECEIVE_AND_OPEN ->
                handleNotification(mapOf(PushNotifications.PAYLOAD_KEY to conversationId), openChat = true)
            NotificationRouting.Action.OPEN -> requestOpen()
            NotificationRouting.Action.RECEIVE, NotificationRouting.Action.IGNORE -> Unit
        }
    }

    /** Unread reached [count]; with `updatesAppBadge`, read replies leave the notification shade. */
    fun unreadChanged(count: Int) {
        if (count != 0 || !ReceptionState.updatesAppBadge) return
        val context = ReceptionState.session?.store?.context ?: return
        PushNotifications.cancelAll(context)
    }
}
