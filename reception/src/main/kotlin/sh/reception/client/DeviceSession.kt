package sh.reception.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import sh.reception.Log
import sh.reception.ReceptionState
import kotlin.coroutines.cancellation.CancellationException

/** One device session: its credentials, cooldowns, halts, retries and serialized device updates. Main thread only. */
internal class DeviceSession(
    api: ReceptionAPI,
    val store: DeviceStore,
    halt: HaltReason? = null,
    val refreshSleep: suspend (Long) -> Unit = { delay(it) },
    now: () -> Long = System::currentTimeMillis,
) {
    enum class HaltReason { APP_UNAVAILABLE, BLOCKED }

    /** Every task of this session; ending the session cancels them all. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val api: ReceptionAPI = api.bound(this, store.chatRevision)
    val cooldowns: ServerCooldowns = ServerCooldowns(store, now, scope)
    val attachmentImages: AttachmentImages = AttachmentImages()

    /** Short-lived bearer token, never persisted; a missing token is refreshed on first use. */
    var accessToken: String? = null

    /** Memory copy of the secure storage item. A new registration is used only once it is stored. */
    var credentials: SessionCredentials? = null
    var credentialsUnsaved: Boolean = false
    var registration: Deferred<String>? = null
    private var launchRegistration: Job? = null
    var refresh: Deferred<String>? = null
    var refreshBackoff: Deferred<Unit>? = null
    var refreshFailures: Int = 0
    var actionClickTask: Job? = null
    var updateGeneration: Int = 0
    var updateTask: Deferred<Unit>? = null
    var preferenceRetryTask: Job? = null
    var synchronizedPaywalls: List<JsonObject>? = null
    var synchronizedLanguage: String? = null
    var synchronizedAppBadge: Boolean? = null
    var synchronizedSubscribed: Boolean? = null
    var synchronizedScreens: List<Map<String, String>>? = null
    var invalidated: Boolean = false
        private set
    var halt: HaltReason? = halt
        private set
    var registrationAllowed: Boolean = false
    var preferenceRetry: RetryState = RetryState()
    val retries: MutableMap<String, RetryState> = mutableMapOf()

    init {
        cooldowns.onChange = { cooldownsChanged() }
        // Old device registrations alone do not prove that the user used support.
        if (!store.hasStartedChat && store.chatCache.load().isNotEmpty()) store.hasStartedChat = true
    }

    val isCurrent: Boolean
        get() = this === ReceptionState.session

    fun requestSucceeded(probe: Boolean) {
        if (invalidated || !probe || halt == null) return
        halt = null
        preferenceRetry = RetryState()
        Log.debug { "Session resumed" }
        if (isCurrent) ReceptionState.sessionResumed()
    }

    fun recordFailure(error: Throwable) {
        if (invalidated || error !is ReceptionAPIError) return
        val reason = when {
            error.status == 401 && error.code == "invalid_app_id" -> HaltReason.APP_UNAVAILABLE
            error.status == 403 && error.code == "app_disabled" -> HaltReason.APP_UNAVAILABLE
            error.status == 403 && error.code == "blocked" -> HaltReason.BLOCKED
            else -> return
        }
        if (halt == reason) {
            Log.debug { "Request skipped, session halted" }
            return
        }
        halt = reason
        suspendPreferenceRetry()
        actionClickTask?.cancel()
        if (isCurrent) {
            ReceptionState.chat?.suspendAutomaticRequests()
            ReceptionState.unreadMonitor.reschedule()
        }
        when {
            reason == HaltReason.BLOCKED -> Log.info { "Session halted, HTTP 403 blocked" }
            error.code == "invalid_app_id" -> Log.error { "Session halted, HTTP 401 invalid_app_id, check the App ID" }
            else -> Log.error { "Session halted, HTTP 403 app_disabled" }
        }
    }

    /**
     * Registers a device the team cannot reach yet, once per launch or foreground; a failure waits for the next one.
     * Registration carries no push token, so the unread refresh that follows sends it with the device update.
     */
    fun registerIfNeeded() {
        if (invalidated || halt != null || isRegistered || launchRegistration != null) return
        lateinit var task: Job
        task = scope.launch(RequestContext(launch = true), start = CoroutineStart.LAZY) {
            try {
                token()
                if (isCurrent) ReceptionState.refreshUnread(updateDevice = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                Log.debug { "Launch registration deferred to the next foreground" }
            } finally {
                if (launchRegistration === task) launchRegistration = null
            }
        }
        launchRegistration = task
        task.start()
    }

    fun becameActive() {
        preferenceRetry.foreground()
        retries.values.forEach { it.foreground() }
        if (halt != null) return
        registerIfNeeded()
        retryPreferencesIfNeeded()
        if (isCurrent) {
            ReceptionState.chat?.retryFailedAutomatically()
            ReceptionState.chat?.startStream()
        }
    }

    fun deactivate() {
        invalidated = true
        credentialsUnsaved = false
        attachmentImages.invalidate()
        cooldowns.invalidate()
        scope.cancel()
        registration = null
        refresh = null
        refreshBackoff = null
        updateTask = null
        preferenceRetryTask = null
        actionClickTask = null
    }

    fun invalidate() {
        deactivate()
        store.clear()
    }
}
