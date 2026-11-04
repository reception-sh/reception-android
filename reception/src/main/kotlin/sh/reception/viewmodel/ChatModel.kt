package sh.reception.viewmodel

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionState
import sh.reception.client.DeviceSession
import sh.reception.client.NetworkMonitor
import sh.reception.client.NotificationAuthorization
import sh.reception.client.Storage
import sh.reception.client.TeamProfiles
import sh.reception.client.cursor
import sh.reception.client.draftText
import sh.reception.client.refreshUnread
import sh.reception.model.Message
import sh.reception.model.MessageStatus
import sh.reception.model.TeamMember

/**
 * The chat screen's state and work for one device session, as iOS `ChatModel`: history, sending, the live stream,
 * reads, connection state and cooldowns. Created lazily by `ReceptionState.chatModel()` and replaced on every reset.
 * Its stored history loads on the storage thread once the session is prepared; until then nothing loads or sends.
 */
@MainThread
internal class ChatModel(val session: DeviceSession?) {
    /** Tasks of this model; [stop] ends them. Tasks start on a later main-looper turn, like iOS `Task {}`. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var messages: List<Message> by mutableStateOf(emptyList())

    /** Fresh reply authors and signed URLs; cached display details are restored separately. */
    var team: Map<String, TeamMember> by mutableStateOf(emptyMap())
        private set
    var teamProfiles: TeamProfiles? by mutableStateOf(null)
        private set
    var sendingDisabled: Boolean by mutableStateOf(false)

    /** The app accepts only verified users and this session is not one; kept in memory only. */
    var verificationRequired: Boolean by mutableStateOf(false)
    var sendingPermissionRevision: Int = 0
    val composer: ComposerModel = ComposerModel(scope)

    /** Opening links from cards and text (`ChatLinks.kt`); tests replace it. */
    var links: LinkHandling = LinkHandling()
    var connection: ConnectionState by mutableStateOf(ConnectionState.HIDDEN)
    var networkMonitor: NetworkMonitor? = null
    var connectionDismissal: Job? = null
    var automaticRetry: AutomaticRetry? = null
    val limitReleaseRetries: MutableSet<String> = mutableSetOf()
    var isLoading: Boolean by mutableStateOf(false)
    var uploadProgress: Double? by mutableStateOf(null)
    var polling: Job? = null

    /** The opening work of the current activation (`ChatActivation.kt`) and its generation. */
    var activation: Job? = null
    var activationGeneration: Int = 0
    var streamId: Any? = null
    var streamConnected: Boolean = false

    /** A stream that stayed up this long has recovered: its next drop starts a fresh backoff. */
    var healthyStreamMillis: Long = 10_000

    /** After eight failures in a row the stream keeps trying at this pace while the chat stays open. */
    var exhaustedStreamRetryMillis: Long = 60_000
    var pathSatisfied: Boolean? = null
    var linkGrace: Job? = null
    var isTyping: Boolean by mutableStateOf(false)
    var typingDismissal: Job? = null
    var userTypingTask: Job? = null
    var userTypingIdle: Job? = null
    var userIsTyping: Boolean = false
    var lastUserTyping: Long = Long.MIN_VALUE
    val sends: MutableMap<String, Job> = mutableMapOf()
    val pendingTimers: MutableMap<String, Job> = mutableMapOf()
    var fetching: Boolean = false
    /** Loading and listening; set by `startPolling`/`stopPolling` in ChatPolling.kt. */
    var isChatActive: Boolean = false
    var maxSeq: Long = 0
    var readCursor: ReadCursor? = null
    var reloadTask: Job? = null
    var pollDeferred: Boolean = false

    /** Null until read when the chat appears; read again on every foreground while it is visible. */
    var notificationAuthorization: NotificationAuthorization? by mutableStateOf(null)

    /** The notification card was answered in this chat; it stays hidden until the next model. */
    var pushPromptAnswered: Boolean by mutableStateOf(false)

    /** Cooldowns are not Compose state; every change bumps this, so what depends on them redraws. */
    var cooldownRevision: Int by mutableIntStateOf(0)

    /** The stored history and draft have loaded. */
    var isRestored: Boolean by mutableStateOf(false)
        private set
    private val restoration: Job

    /** The chats on screen and the active ones that show this model (two embedded views share it). */
    val startedOwners: MutableSet<Any> = mutableSetOf()
    val resumedOwners: MutableSet<Any> = mutableSetOf()

    /** On screen; also `Reception.isChatOpen`, and the unread timer pauses while it holds. */
    var isVisible: Boolean by mutableStateOf(false)
        private set

    val canSend: Boolean
        get() = isRestored && current && (!sendingDisabled || session?.halt == DeviceSession.HaltReason.BLOCKED)

    val current: Boolean
        get() = session != null && session === ReceptionState.session && !session.invalidated

    init {
        composer.onTextChange = { text ->
            if (isRestored && current) session?.store?.draftText = text
            sendTyping(text.isNotBlank())
        }
        restoration = scope.launch { restore() }
    }

    /** Waits for the stored history; a session that ended meanwhile never restores. */
    suspend fun restored() {
        restoration.join()
    }

    private suspend fun restore() {
        val session = session ?: return
        try {
            session.prepared()
        } catch (_: Exception) {
            return
        }
        if (!current) return
        val store = session.store
        val cached = Storage.run { store.chatCache.load() }
        if (!current) return
        // Interrupted submissions use the same serial recovery path, after history reconciliation.
        messages = cached.map { message ->
            if (message.seq == null && message.status == MessageStatus.SENDING) {
                message.copy(status = MessageStatus.FAILED, retryAfterTransientFailure = true)
            } else {
                message
            }
        } + messages
        maxSeq = store.cursor
        // Text written while the history loaded is the newer draft.
        if (composer.isEdited) store.draftText = composer.text else composer.restore(store.draftText)
        // Only a conversation response names the team; until then the cached names and photos stand.
        teamProfiles = TeamProfiles(scope, store.teamProfileCache)
        isRestored = true
    }

    fun replaceTeam(team: Map<String, TeamMember>) {
        this.team = team
        teamProfiles?.let(::updateTeamProfiles)
    }

    private fun updateTeamProfiles(profiles: TeamProfiles) {
        val appearance = ReceptionAppearance.resolved
        profiles.update(if (appearance.showsTeamNames || appearance.showsTeamPhotos) team else emptyMap(),
            showsPhotos = appearance.showsTeamPhotos)
    }

    fun markVisible(visible: Boolean) {
        if (isVisible == visible) return
        isVisible = visible
        if (ReceptionState.chat === this) ReceptionState.isChatOpen.value = visible
        ReceptionState.unreadMonitor.reschedule()
    }

    fun saveMessages() {
        val session = session ?: return
        if (!current || !isRestored) return
        val snapshot = messages
        val cache = session.store.chatCache
        Storage.launch { cache.save(snapshot) }
    }

    fun update(id: String, change: (Message) -> Message) {
        messages = messages.map { if (it.id == id) change(it) else it }
    }

    /** The unread refresh the facade runs; the session part lives in `UnreadRefresh.kt`. */
    suspend fun refreshUnread(updateDevice: Boolean, probe: Boolean) {
        if (!current) return
        session?.refreshUnread(updateDevice, probe)
    }

    /** A probe through a halted session succeeded, so the device may send again. */
    fun sendingResumed() {
        sendingDisabled = false
    }

    /** Ends the polling, stream, sends and timers of a chat whose session ended. */
    fun stop() {
        sends.values.forEach { it.cancel() }
        pendingTimers.values.forEach { it.cancel() }
        stopPolling()
        markVisible(false)
        scope.cancel()
    }
}
