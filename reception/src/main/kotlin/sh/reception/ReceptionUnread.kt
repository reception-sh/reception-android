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

/** The unread count `Reception.unreadCount` publishes, and the one refresh of the current session that updates it. */
internal object ReceptionUnread {
    val count: MutableStateFlow<Int> = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshTask: Job? = null

    fun set(count: Int) {
        this.count.value = maxOf(0, count)
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
}
