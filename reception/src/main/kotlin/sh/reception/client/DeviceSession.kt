package sh.reception.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import sh.reception.Log
import sh.reception.ReceptionState
import kotlin.coroutines.cancellation.CancellationException

/**
 * One device session: its credentials, cooldowns, halts, retries and serialized device updates. Main thread only.
 * Its stored values load on the [Storage] thread once [after] has finished (the previous session's deletion); until
 * then it stages identity changes in memory, and every request waits for [prepared].
 */
internal class DeviceSession(
    api: ReceptionAPI,
    val store: DeviceStore,
    halt: HaltReason? = null,
    private val after: Job? = null,
    /** Set for the session `configure` starts, which resets a chat restored from a backup without its session. */
    private val checksRestoredChat: Boolean = false,
    val refreshSleep: suspend (Long) -> Unit = { delay(it) },
    now: () -> Long = System::currentTimeMillis,
) {
    enum class HaltReason { APP_UNAVAILABLE, BLOCKED }

    /** Every task of this session; ending the session cancels them all. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val api: ReceptionAPI = api.bound(this)
    val cooldowns: ServerCooldowns = ServerCooldowns(store, now, scope)
    val attachmentImages: AttachmentImages = AttachmentImages()

    /** Short-lived bearer token, never persisted; a missing token is refreshed on first use. */
    var accessToken: String? = null

    /** Memory copy of the secure storage item. A new registration is used only once it is stored. */
    var credentials: SessionCredentials? = null
    var credentialsUnsaved: Boolean = false

    /** The last secure storage read failed; the next use reads again instead of treating it as "no session". */
    var credentialsUnreadable: Boolean = false

    /** The identity sent with registration and device updates, in memory; the store follows asynchronously. */
    var pending: DeviceIdentity = DeviceIdentity()
        private set
    private val stagedChanges = mutableListOf<(DeviceIdentity) -> DeviceIdentity>()
    private val preparedActions = mutableListOf<() -> Unit>()
    var isPrepared: Boolean = false
        private set
    var chatRevision: Int = 0
        private set
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
    }

    // Declared last: with the immediate main dispatcher it starts running inside the constructor.
    private val preparation: Deferred<Unit> = scope.async { prepare() }

    /** Waits until the stored values are loaded; a session that ended meanwhile fails with `cancelled`. */
    suspend fun prepared() {
        if (!isPrepared) preparation.awaitShared()
    }

    /** Runs [action] once the stored values are loaded: now, or right before the first request continues. */
    fun afterPrepared(action: () -> Unit) {
        if (invalidated) return
        if (isPrepared) action() else preparedActions += action
    }

    /** Changes the identity in memory at once; the store saves it asynchronously, merged over its value if not loaded yet. */
    fun stage(change: (DeviceIdentity) -> DeviceIdentity) {
        pending = change(pending)
        if (isPrepared) store.pending = pending else stagedChanges += change
    }

    private class Loaded(val chatReset: Boolean, val credentials: SessionCredentials?, val unreadable: Boolean)

    private suspend fun prepare() {
        after?.join()
        val loaded = Storage.run {
            val chatReset = store.open(checkRestoredChat = checksRestoredChat)
            try {
                Loaded(chatReset, store.loadCredentials(), unreadable = false)
            } catch (_: ReceptionAPIError) {
                Loaded(chatReset, null, unreadable = true)
            }
        }
        if (invalidated) return
        if (loaded.chatReset) Log.info { "Local chat reset, its session is not stored on this device" }
        credentials = loaded.credentials
        credentialsUnreadable = loaded.unreadable
        chatRevision = store.chatRevision
        pending = stagedChanges.fold(store.pending) { identity, change -> change(identity) }
        if (stagedChanges.isNotEmpty()) store.pending = pending
        stagedChanges.clear()
        cooldowns.restore()
        isPrepared = true
        val actions = preparedActions.toList()
        preparedActions.clear()
        actions.forEach { if (!invalidated) it() }
        if (isCurrent) ReceptionState.sessionPrepared()
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
                prepared()
                // A stored session needs no registration; preparing it refreshes unread instead.
                if (isRegistered) return@launch
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
        store.close()
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

    /** Ends the session and deletes its stored values once [after] have finished. */
    fun invalidate(vararg after: Job?): Job {
        deactivate()
        return Storage.launch(*after) { store.reset() }
    }
}
