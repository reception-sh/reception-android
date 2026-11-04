package sh.reception

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.isRegistered

/**
 * The unread count `Reception.unreadCount` publishes, the one refresh of the current session that updates it, and the
 * read generation that lets a newer read or send replace older unread answers.
 */
internal object ReceptionUnread {
    val count: MutableStateFlow<Int> = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshTask: Job? = null
    var readGeneration: Int = 0
        private set

    /** Without [syncsNotifications] (configure), read notifications stay until the first server answer. */
    fun set(count: Int, syncsNotifications: Boolean = true) {
        this.count.value = maxOf(0, count)
        if (syncsNotifications) ReceptionPush.unreadChanged(this.count.value)
    }

    fun cancelRefresh() {
        refreshTask?.cancel()
        refreshTask = null
    }

    fun refresh(updateDevice: Boolean = false, probe: Boolean = false) {
        if (updateDevice) cancelRefresh()
        val session = ReceptionState.session
        if (session?.isRegistered != true || (session.halt != null && !probe) || refreshTask != null) return
        lateinit var task: Job
        task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                do {
                    val current = ReceptionState.session ?: return@launch
                    ReceptionState.chatModel().refreshUnread(updateDevice, probe)
                    if (!isActive || current.halt != null || probe || current.retries["unread"]?.delay == null) return@launch
                } while (ApplicationLifecycle.isActive)
            } finally {
                if (refreshTask === task) refreshTask = null
            }
        }
        refreshTask = task
        task.start()
    }

    /** A read or a send confirmation makes older unread answers and reads obsolete. */
    fun invalidateRead(): Int {
        readGeneration += 1
        return readGeneration
    }

    /** A read of generation [generation] reported [count]; a newer read or send has replaced an older one. */
    fun noteRead(count: Int, generation: Int) {
        if (generation != readGeneration) return
        invalidateRead()
        set(count)
    }
}
