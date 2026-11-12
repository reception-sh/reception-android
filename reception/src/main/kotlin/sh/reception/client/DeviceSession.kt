package sh.reception.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import sh.reception.Log
import sh.reception.ReceptionState

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
    var launchRegistration: Job? = null
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
    /** Set and cleared by the responses in SessionLifecycle.kt. */
    var halt: HaltReason? = halt
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
