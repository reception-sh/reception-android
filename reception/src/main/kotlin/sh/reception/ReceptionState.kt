package sh.reception

import android.app.Application
import android.content.Context
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
import sh.reception.client.UnreadMonitor
import sh.reception.client.isRegistered
import sh.reception.client.refreshUnread

/** The facade's internal state and the session lifecycle behind it. Main thread only. */
internal object ReceptionState {
    var session: DeviceSession? = null
        private set

    /** The configured App ID. */
    private var appId: String? = null
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
        if (this.appId == appId) {
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
        this.appId = appId
        ReceptionIdentity.forget()
        val api = ReceptionAPI(appId)
        ReceptionAppearance.configuration.configure(context, api, appId)
        var store = DeviceStore(context, appId + DeviceStore.generation(context, appId), deviceScope = appId)
        if (store.hasConversation && store.credentialsMissing) {
            // Keystore keys stay on their device, so a restored chat without its session is reset like logout.
            store.clear()
            store = DeviceStore(context, appId + DeviceStore.generation(context, appId, rotate = true), deviceScope = appId)
            Log.info { "Local chat reset, its session is not stored on this device" }
        }
        session = DeviceSession(api, store)
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

    fun resetLocal(dismissChat: Boolean = true) {
        unreadMonitor.setActive(false)
        cancelUnreadRefresh()
        val session = session ?: return
        val appId = appId ?: return
        val context = session.store.context
        session.invalidate()
        chat?.stop()
        chat = null
        this.session = DeviceSession(session.api,
            DeviceStore(context, appId + DeviceStore.generation(context, appId, rotate = true), deviceScope = appId),
            halt = session.halt)
        setUnread(0)
        revision += 1
        if (dismissChat) advancePresentationRevision()
    }

    fun resetChat(revision: Int, deviceDeleted: Boolean = false) {
        val session = session ?: return
        if (deviceDeleted) {
            val identity = session.store.pending
            resetLocal(dismissChat = false)
            this.session?.store?.pending = identity
            return
        }
        unreadMonitor.setActive(false)
        cancelUnreadRefresh()
        session.deactivate()
        chat?.stop()
        session.store.resetChat(revision)
        chat = null
        this.session = DeviceSession(session.api, session.store.renewed())
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
