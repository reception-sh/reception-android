package sh.reception

import androidx.annotation.VisibleForTesting
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

    // `Reception.addUnreadCountListener`, for Java hosts that cannot collect the StateFlow.
    private val listeners = mutableListOf<(Int) -> Unit>()

    /** Without [syncsNotifications] (configure), read notifications stay until the first server answer. */
    fun set(count: Int, syncsNotifications: Boolean = true) {
        val previous = this.count.value
        this.count.value = maxOf(0, count)
        if (this.count.value != previous) notifyListeners(this.count.value)
        if (syncsNotifications) ReceptionPush.unreadChanged(this.count.value)
    }

    /** Delivers the current count at once and every change after it; adding the same listener again does nothing. */
    fun addListener(listener: (Int) -> Unit) {
        if (listeners.any { it === listener }) return
        listeners += listener
        listener(count.value)
    }

    fun removeListener(listener: (Int) -> Unit) {
        listeners.removeAll { it === listener }
    }

    /** Advanced by every publication, so a delivery that a listener superseded (by a logout, say) stops. */
    private var publication = 0

    /**
     * A listener that an earlier one removed during this delivery is not called. A listener whose call publishes a
     * newer count ends this delivery: the nested one already reached everybody, and the older count must not follow it.
     */
    private fun notifyListeners(count: Int) {
        publication += 1
        val current = publication
        for (listener in listeners.toList()) {
            if (publication != current) return
            if (listeners.any { it === listener }) listener(count)
        }
    }

    /** The refresh in flight, for tests that wait for it. */
    @get:VisibleForTesting(otherwise = VisibleForTesting.NONE)
    val runningRefresh: Job?
        get() = refreshTask

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
