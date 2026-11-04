package sh.reception.client

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl
import java.util.UUID

/**
 * Everything a device session keeps on disk, in a SharedPreferences namespace per session scope. A logout or reset
 * rotates the scope's generation, so a new session never reads the old one's values even if a delete failed.
 *
 * [open] runs on the [Storage] thread before anything else: it resolves the generation and loads the preferences file,
 * so the main thread afterwards reads and writes memory only (writes reach the disk asynchronously). Secure storage,
 * the caches and the deletions stay on the storage thread.
 */
internal class DeviceStore private constructor(
    val context: Context,
    /** The App ID's namespace ([namespace]), without the generation. */
    private val namespace: String,
    val credentialStorage: CredentialStorage,
    /** Set when a reset chat keeps the session's namespace. */
    resolvedScope: String?,
) {
    constructor(context: Context, namespace: String, credentialStorage: CredentialStorage = CredentialStorage(context)) :
        this(context, namespace, credentialStorage, null)

    /** Where the session credentials live; tests replace it. Every function blocks, so it runs on [Storage]. */
    class CredentialStorage(
        val read: (String) -> SecureStore.ReadResult,
        val save: (String, String) -> Boolean,
        val delete: (String) -> Boolean,
    ) {
        constructor(store: SecureStore) : this(store::read, { value, account -> store.save(value, account) }, store::delete)

        constructor(context: Context) : this(SecureStore.shared(context))
    }

    private val defaults: SharedPreferences by lazy { preferences(context) }
    private var scope: String = resolvedScope.orEmpty()

    /** This session's values; the chat's are in DeviceStoreChat.kt. */
    val values = ScopedPreferences({ defaults }) { scope }

    /** False once the chat is discarded: late drafts and link taps are dropped. */
    @Volatile
    var acceptsDrafts = true
        private set

    @Volatile
    var isOpen: Boolean = false
        private set
    lateinit var chatCache: ChatCache
        private set
    lateinit var teamProfileCache: TeamProfileCache
        private set

    /**
     * Storage thread. Resolves the generation and loads everything the main thread reads. With [checkRestoredChat], a
     * chat restored from a backup without its session (Keystore keys stay on their device) is reset like a logout;
     * returns true then.
     */
    fun open(checkRestoredChat: Boolean = false): Boolean {
        if (isOpen) return false
        if (scope.isEmpty()) scope = namespace + generation(context, namespace)
        openCaches()
        var reset = false
        if (checkRestoredChat && hasConversation && credentialsMissing) {
            deleteAll()
            scope = namespace + generation(context, namespace)
            acceptsDrafts = true
            openCaches()
            reset = true
        }
        pushActiveState.value = values.optionalBoolean("pushActive")
        // Unconditionally: the unread schedule reads it on the main thread later, where the disk is off limits.
        chatCache.restoreLatestMessageAt()
        // Old device registrations alone do not prove that the user used support.
        if (!hasStartedChat && chatCache.latestMessageAt != null) hasStartedChat = true
        isOpen = true
        return reset
    }

    private fun openCaches() {
        chatCache = ChatCache(context, scope)
        teamProfileCache = TeamProfileCache(context, scope)
    }

    /** The same namespace and generation for a session that continues after a chat reset. */
    fun renewed(): DeviceStore = DeviceStore(context, namespace, credentialStorage, scope)

    /** Main thread: forgets the chat's values; [clearChatFiles] deletes its files on the storage thread. */
    fun resetChat(revision: Int) {
        acceptsDrafts = false
        values.remove(chatKeys)
        pushActiveState.value = null
        chatRevision = revision
    }

    fun clearChatFiles() {
        chatCache.clear()
        teamProfileCache.clear()
    }

    /**
     * `pushActive` as Compose state, so the chat's status line follows every server answer; the preferences keep it
     * across launches (DeviceStoreChat.kt). Loaded at [open].
     */
    val pushActiveState: MutableState<Boolean?> = mutableStateOf(null)

    /** "No thanks" on the notification card, for this device and App ID; logout and chat resets keep it. */
    var pushPromptDeclined: Boolean
        get() = values.unscopedBoolean("$namespace.pushPromptDeclined")
        set(value) = values.putUnscopedBoolean("$namespace.pushPromptDeclined", value)

    /** The system's notification permission dialog was answered from the card, for this device and App ID. */
    var notificationPermissionAnswered: Boolean
        get() = values.unscopedBoolean("$namespace.notificationPermissionAnswered")
        set(value) = values.putUnscopedBoolean("$namespace.notificationPermissionAnswered", value)

    /** Server waits of this session; a new session namespace starts without them. */
    var cooldowns: List<ServerCooldowns.Cooldown>
        get() = values.decoded("cooldowns", ListSerializer(ServerCooldowns.Cooldown.serializer())) ?: emptyList()
        set(value) = values.encoded("cooldowns", ListSerializer(ServerCooldowns.Cooldown.serializer()), value)

    /** The last store state of each subscription, so only a change is reported to automations. */
    var subscriptionStates: Map<String, String>
        get() = values.decoded("subscriptionStates", stringMap) ?: emptyMap()
        set(value) = values.encoded("subscriptionStates", stringMap, value)

    var lastLimitCheck: Long?
        get() = values.long("lastLimitCheck")
        set(value) = values.putLong("lastLimitCheck", value)

    var pending: DeviceIdentity
        get() = values.decoded("pending", DeviceIdentity.serializer()) ?: DeviceIdentity()
        set(value) = values.encoded("pending", DeviceIdentity.serializer(), value)

    /** Late writes from a discarded chat are dropped from now on. */
    fun close() {
        acceptsDrafts = false
    }

    /** Storage thread: deletes this session's values and rotates the generation, so even a failed delete is never reused. */
    fun reset() {
        if (!isOpen) open()
        deleteAll()
    }

    private fun deleteAll() {
        acceptsDrafts = false
        // A new namespace prevents reuse even when secure storage refuses deletion.
        credentialStorage.delete(values.key("session"))
        clearChatFiles()
        values.remove(chatKeys + listOf("chatRevision", "pending", "cooldowns", "lastLimitCheck", "subscriptionStates"))
        pushActiveState.value = null
        generation(context, namespace, rotate = true)
    }

    companion object {
        private const val PREFERENCES = "sh.reception.sdk"
        private val stringMap = MapSerializer(String.serializer(), String.serializer())

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        /**
         * The namespace of [appId] at [serviceUrl] (SDK_SPEC.md, Server cooldowns: per service address, App ID and
         * local session). The hosted service keeps the bare App ID, so production keeps its stored sessions.
         */
        fun namespace(appId: String, serviceUrl: HttpUrl): String {
            val address = normalized(serviceUrl)
            return if (address == normalized(ReceptionAPI.defaultServiceUrl)) appId else "$address|$appId"
        }

        /** Scheme, host, port and path, without query, fragment or a trailing slash. */
        fun normalized(serviceUrl: HttpUrl): String =
            serviceUrl.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')

        /** Storage thread. */
        fun generation(context: Context, scope: String, rotate: Boolean = false): String {
            val defaults = preferences(context)
            val key = "$scope.generation"
            if (!rotate) {
                val value = try {
                    defaults.getString(key, null)
                } catch (_: ClassCastException) {
                    null
                }
                if (value != null) return value
            }
            val value = UUID.randomUUID().toString().uppercase()
            defaults.edit { putString(key, value) }
            return value
        }
    }
}
