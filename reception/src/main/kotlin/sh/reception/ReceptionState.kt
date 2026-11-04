package sh.reception

import android.app.Application
import android.content.Context
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
import sh.reception.client.DeviceSession
import sh.reception.client.DeviceStore
import sh.reception.client.LogoutRevocations
import sh.reception.client.ReceptionAPI
import sh.reception.client.ReceptionAPIError
import sh.reception.client.SecureStore
import sh.reception.client.Storage
import sh.reception.client.UnreadMonitor
import sh.reception.client.isRegistered
import sh.reception.client.refreshUnread

/** The facade's internal state and the session lifecycle behind it. Main thread only. */
internal object ReceptionState {
    var session: DeviceSession? = null
        private set

    /** The configured App ID's storage namespace, which includes the service address of a test service. */
    private var namespace: String? = null
    var chat: ChatHooks? = null
    var updatesAppBadge: Boolean = true
    var onEvent: ((ReceptionEvent) -> Unit)? = null
    var readGeneration: Int = 0
        private set
    var revision: Int = 0
        private set
    var presentationRevision: Int = 0
        private set
    val unreadCount: MutableStateFlow<Int> = MutableStateFlow(0)
    val isChatOpen: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val unreadMonitor: UnreadMonitor = UnreadMonitor()
    var logoutRevocations: LogoutRevocations? = null
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshTask: Job? = null

    /** The latest deletion on the storage thread; a session opened later waits for it. */
    private var storageWork: Job? = null

    fun configure(context: Context, appId: String) {
        if (!ReceptionAPI.accepts(appId)) {
            Log.error {
                if (appId.startsWith("ids_")) {
                    "Configure refused the identity secret, which belongs only on your server; pass the App ID (app_…)"
                } else {
                    "Configure refused a malformed App ID; copy it from your dashboard"
                }
            }
            return
        }
        val namespace = DeviceStore.namespace(appId, ReceptionAPI.serviceUrl)
        if (this.namespace == namespace) {
            Log.debug { "Configure ignored, same App ID already active" }
            return
        }
        ReceptionLocalization.context = context
        (context as? Application)?.let(ApplicationLifecycle::start)
        unreadMonitor.setActive(false)
        cancelUnreadRefresh()
        session?.deactivate()
        chat?.stop()
        chat = null
        this.namespace = namespace
        ReceptionIdentity.forget()
        val api = ReceptionAPI(appId)
        ReceptionAppearance.configuration.configure(context, api, namespace)
        AppLocale.prepare(context)
        // The store opens on the storage thread; registration and every request wait for it.
        session = DeviceSession(api, DeviceStore(context, namespace), after = storageWork, checksRestoredChat = true)
        // No badge sync before the first server sync; the server value is the source of truth.
        unreadCount.value = 0
        revision += 1
        advancePresentationRevision()
        val revocations = logoutRevocations ?: LogoutRevocations(SecureStore.shared(context)).also { logoutRevocations = it }
        revocations.resumed(api)
        val active = ApplicationLifecycle.isActive
        unreadMonitor.setActive(active)
        if (active) session?.registerIfNeeded()
        ReceptionAppearance.updateRefresh()
        Log.info { "Configured, SDK ${SDKVersion.current}" }
    }

    /** Ends the configured session and forgets the App ID, so each test starts unconfigured. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun resetForTests() {
        unreadMonitor.setActive(false)
        cancelUnreadRefresh()
        session?.deactivate()
        session = null
        chat = null
        namespace = null
        storageWork = null
        logoutRevocations?.suspend()
        logoutRevocations = null
        ReceptionIdentity.forget()
        unreadCount.value = 0
        ReceptionAPI.serviceUrl = ReceptionAPI.defaultServiceUrl
    }

    fun applicationBecameActive() {
        ReceptionAppearance.becameActive()
        unreadMonitor.becameActive()
        logoutRevocations?.becameActive()
    }

    fun applicationEnteredBackground() {
        ReceptionAppearance.enteredBackground()
        unreadMonitor.enteredBackground()
        logoutRevocations?.suspend()
    }

    /** Ends the session in memory at once; its stored values are deleted on the storage thread after [after]. */
    fun resetLocal(dismissChat: Boolean = true, after: Job? = null) {
        unreadMonitor.setActive(false)
        cancelUnreadRefresh()
        val session = session ?: return
        val namespace = namespace ?: return
        val context = session.store.context
        val deleted = session.invalidate(storageWork, after)
        storageWork = deleted
        chat?.stop()
        chat = null
        // The deletion rotates the generation, so the new session opens a fresh namespace after it.
        this.session = DeviceSession(session.api, DeviceStore(context, namespace), halt = session.halt, after = deleted)
        setUnread(0)
        revision += 1
        if (dismissChat) advancePresentationRevision()
    }

    fun resetChat(revision: Int, deviceDeleted: Boolean = false) {
        val session = session ?: return
        if (deviceDeleted) {
            val identity = session.pending
            resetLocal(dismissChat = false)
            this.session?.stage { identity }
            return
        }
        unreadMonitor.setActive(false)
        cancelUnreadRefresh()
        session.deactivate()
        chat?.stop()
        session.store.resetChat(revision)
        val store = session.store
        val cleared = Storage.launch(storageWork) { store.clearChatFiles() }
        storageWork = cleared
        chat = null
        this.session = DeviceSession(session.api, store.renewed(), after = cleared)
        setUnread(0)
        this.revision += 1
    }

    /** A `409 chat_reset` from the current session clears the chat and keeps the session. */
    fun handleReset(session: DeviceSession, error: Throwable): Boolean {
        if (session !== this.session || error !is ReceptionAPIError) return false
        session.recordFailure(error)
        val resetRevision = error.resetRevision
        if (error.status != 409 || error.code != "chat_reset" || resetRevision == null || resetRevision < 0) return false
        Log.info { "Chat reset by the team, local history cleared, revision $resetRevision" }
        resetChat(resetRevision)
        return true
    }

    /** The current session's stored values are loaded: what configure could not do without them happens now. */
    fun sessionPrepared() {
        unreadMonitor.sessionPrepared()
    }

    fun sessionResumed() {
        val session = session ?: return
        chat?.sendingResumed()
        logoutRevocations?.resumed(session.api)
        ReceptionAppearance.sessionResumed()
        unreadMonitor.reschedule()
        chat?.startStream()
    }

    fun setUnread(count: Int) {
        unreadCount.value = maxOf(0, count)
    }

    fun cancelUnreadRefresh() {
        refreshTask?.cancel()
        refreshTask = null
    }

    private fun advancePresentationRevision() {
        presentationRevision += 1
    }

    fun refreshUnread(updateDevice: Boolean = false, probe: Boolean = false) {
        if (updateDevice) cancelUnreadRefresh()
        val session = session
        if (session?.isRegistered != true || (session.halt != null && !probe) || refreshTask != null) return
        lateinit var task: Job
        task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                do {
                    val current = this@ReceptionState.session ?: return@launch
                    current.refreshUnread(updateDevice, probe)
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
