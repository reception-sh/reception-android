package sh.reception.client

import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.ReceptionState

/**
 * Refreshes unread at launch and every foreground, and polls on the service's schedule while the chat is hidden, the
 * app is active, a conversation is open and push cannot deliver.
 */
internal class UnreadMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var notificationsAuthorized = false
    private var enabled = false
    private var active = false

    fun becameActive() {
        ReceptionState.session?.becameActive()
        if (enabled) setActive(true, foreground = true)
    }

    fun enteredBackground() {
        if (enabled) setActive(false)
    }

    fun setActive(active: Boolean, foreground: Boolean = false) {
        enabled = true
        this.active = active
        task?.cancel()
        task = null
        val session = ReceptionState.session
        if (!active) {
            ReceptionState.cancelUnreadRefresh()
            session?.suspendPreferenceRetry()
            return
        }
        // Android answers at once; iOS asks asynchronously, so its refresh follows the answer the same way.
        notificationsAuthorized = session?.let { NotificationManagerCompat.from(it.store.context).areNotificationsEnabled() } == true
        if (session?.isRegistered != true) return
        val blockedProbe = foreground && session.halt == DeviceSession.HaltReason.BLOCKED
        ReceptionState.refreshUnread(updateDevice = !blockedProbe, probe = blockedProbe)
    }

    /** The launch refresh of a stored session, which configure had to leave until its values were loaded. */
    fun sessionPrepared() {
        if (enabled && active) setActive(true)
    }

    fun reschedule() {
        task?.cancel()
        task = null
        val session = ReceptionState.session?.takeIf { it.isPrepared } ?: return
        val store = session.store
        if (!active || !ApplicationLifecycle.isActive || session.halt != null || session.retries["unread"]?.stopped == true ||
            !store.hasConversation || ReceptionState.isChatOpen.value
        ) return
        val lastActivity = listOfNotNull(store.lastMessageAt, store.chatCache.latestMessageAt).maxOrNull()
        val pushActive = store.pushActive == true && notificationsAuthorized
        val interval = UnreadSchedule.interval(store.conversationStatus, lastActivity, pushActive, store.unreadPolling,
            System.currentTimeMillis()) ?: return
        task = scope.launch {
            delay(interval)
            val current = ReceptionState.session
            if (!active || !ApplicationLifecycle.isActive || current?.halt != null ||
                current?.store?.hasConversation != true || ReceptionState.isChatOpen.value
            ) return@launch
            task = null
            ReceptionState.refreshUnread()
        }
    }
}
