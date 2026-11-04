package sh.reception

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.DeviceSession
import sh.reception.client.DeviceStore
import sh.reception.client.LogoutRevocations
import sh.reception.client.ReceptionAPI
import sh.reception.client.SecureStore
import sh.reception.client.SetupCheck
import sh.reception.client.UnreadMonitor
import sh.reception.client.registerIfNeeded
import sh.reception.ui.ChatPresenter
import sh.reception.viewmodel.ChatModel

/**
 * The facade's internal state: the configured App ID, the current session and its chat model. Configure starts it;
 * [ReceptionResets] replaces the session after a logout, deletion or reset, and [ReceptionLifecycle] follows the app's
 * foreground. Main thread only.
 */
internal object ReceptionState {
    var session: DeviceSession? = null
        private set

    private val configuredNamespace = MutableStateFlow<String?>(null)

    /** The configured App ID's storage namespace, which includes the service address of a test service. */
    var namespace: String?
        get() = configuredNamespace.value
        private set(value) {
            configuredNamespace.value = value
        }

    /** The namespace once an App ID is configured, waiting up to [timeoutMillis]; null when none came in time. */
    suspend fun configuredNamespace(timeoutMillis: Long): String? =
        withTimeoutOrNull(timeoutMillis) { configuredNamespace.first { it != null } }
    /** The chat model of the current session, created on first use and replaced on every reset. */
    var chat: ChatModel? = null
        private set
    var updatesAppBadge: Boolean = true
    var onEvent: ((ReceptionEvent) -> Unit)? = null
    /**
     * Compose state: the current session's [DeviceStore.chatIdentity] once its store is loaded, null before. An open
     * chat rebuilds with the new session's model when it changes; being persisted, it is the same in a process that
     * Android restarts for a result, so the chat's saved state (its result launchers) finds its place again.
     */
    var chatKey: String? by mutableStateOf(null)
        private set
    var presentationRevision: Int = 0
        private set
    val isChatOpen: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val unreadMonitor: UnreadMonitor = UnreadMonitor()
    var logoutRevocations: LogoutRevocations? = null
        private set

    /** The latest deletion on the storage thread; a session opened later waits for it. */
    var storageWork: Job? = null
        private set

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
        // A first configure has nothing presented to cancel, and keeps a sheet restored after the process died.
        val reconfigured = this.namespace != null
        ReceptionLocalization.context = context
        (context as? Application)?.let(ApplicationLifecycle::start)
        unreadMonitor.setActive(false)
        ReceptionUnread.cancelRefresh()
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
        ReceptionUnread.set(0, syncsNotifications = false)
        chatKey = null
        advancePresentationRevision(dismissesChat = reconfigured)
        val revocations = logoutRevocations ?: LogoutRevocations(SecureStore.shared(context)).also { logoutRevocations = it }
        revocations.resumed(api)
        val active = ApplicationLifecycle.isActive
        unreadMonitor.setActive(active)
        if (active) session?.registerIfNeeded()
        ReceptionAppearance.updateRefresh()
        ReceptionPaywalls.refresh()
        ReceptionAutomations.checkSubscriptions()
        ReceptionPush.registerIfAuthorized()
        Log.info { "Configured, SDK ${SDKVersion.current}" }
        ReceptionPush.configured()
        SetupCheck.configured(context, appId)
    }

    /** Ends the configured session and forgets the App ID, so each test starts unconfigured. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun resetForTests() {
        unreadMonitor.setActive(false)
        ReceptionUnread.cancelRefresh()
        session?.deactivate()
        session = null
        chat?.stop()
        chat = null
        chatKey = null
        namespace = null
        storageWork = null
        logoutRevocations?.suspend()
        logoutRevocations = null
        ReceptionIdentity.forget()
        ReceptionUnread.set(0, syncsNotifications = false)
        ReceptionAPI.serviceUrl = ReceptionAPI.defaultServiceUrl
        ChatPresenter.resetForTests()
        SetupCheck.resetForTests()
    }

    /** The current session's stored values are loaded: what configure could not do without them happens now. */
    fun sessionPrepared() {
        chatKey = session?.store?.chatIdentity
        unreadMonitor.sessionPrepared()
    }

    /** The chat model of the ended session stops; the next use creates one for the new session. */
    fun stopChat() {
        chat?.stop()
        chat = null
    }

    /**
     * After a reset ([ReceptionResets]): [next] becomes the current session, and an open chat rebuilds with it once its
     * store is loaded. [storage] is the reset's disk work, which every session opened later waits for.
     */
    fun replaceSession(next: DeviceSession, storage: Job?) {
        storageWork = storage
        session = next
        chatKey = null
    }

    fun chatModel(): ChatModel = chat ?: ChatModel(session).also { chat = it }

    /** A configure, logout or deletion: work queued for the earlier presentation state (an automation's open) stops. */
    fun advancePresentationRevision(dismissesChat: Boolean = true) {
        presentationRevision += 1
        if (dismissesChat) ChatPresenter.dismiss()
    }
}
