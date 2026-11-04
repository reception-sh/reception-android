package sh.reception.client

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    /** The App ID's namespace, without the generation. */
    private val namespace: String,
    private val credentialStorage: CredentialStorage,
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

    @Volatile
    private var acceptsDrafts = true
    private var scope: String = resolvedScope.orEmpty()

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
        // Old device registrations alone do not prove that the user used support.
        if (!hasStartedChat && chatCache.latestMessageAt != null) hasStartedChat = true
        isOpen = true
        return reset
    }

    private fun openCaches() {
        chatCache = ChatCache(context, scope)
        teamProfileCache = TeamProfileCache(context, scope)
    }

    var chatRevision: Int
        get() = read { getInt(key("chatRevision"), 0) } ?: 0
        set(value) = write { putInt(key("chatRevision"), value) }

    /** The same namespace and generation for a session that continues after a chat reset. */
    fun renewed(): DeviceStore = DeviceStore(context, namespace, credentialStorage, scope)

    /** Main thread: forgets the chat's values; [clearChatFiles] deletes its files on the storage thread. */
    fun resetChat(revision: Int) {
        acceptsDrafts = false
        remove(CHAT_KEYS)
        chatRevision = revision
    }

    fun clearChatFiles() {
        chatCache.clear()
        teamProfileCache.clear()
    }

    var hasStartedChat: Boolean
        get() = read { getBoolean(key("hasStartedChat"), false) } == true || hasConversation
        set(value) = write { putBoolean(key("hasStartedChat"), value) }

    var hasConversation: Boolean
        get() = read { getBoolean(key("hasConversation"), false) } == true
        set(value) = write { putBoolean(key("hasConversation"), value) }

    var conversationStatus: String?
        get() = read { getString(key("conversationStatus"), null) }
        set(value) = write { putString(key("conversationStatus"), value) }

    /** Epoch milliseconds of the latest message the service reported. */
    var lastMessageAt: Long?
        get() = read { if (contains(key("lastMessageAt"))) getLong(key("lastMessageAt"), 0) else null }
        set(value) = write { if (value == null) remove(key("lastMessageAt")) else putLong(key("lastMessageAt"), value) }

    var pushActive: Boolean?
        get() = read { if (contains(key("pushActive"))) getBoolean(key("pushActive"), false) else null }
        set(value) = write { if (value == null) remove(key("pushActive")) else putBoolean(key("pushActive"), value) }

    /** "No thanks" on the notification card, for this device and App ID; logout and chat resets keep it. */
    var pushPromptDeclined: Boolean
        get() = read { getBoolean("$namespace.pushPromptDeclined", false) } == true
        set(value) = write { putBoolean("$namespace.pushPromptDeclined", value) }

    var unreadPolling: List<UnreadStage>?
        get() = decoded("unreadPolling", ListSerializer(UnreadStage.serializer()))
        set(value) = encoded("unreadPolling", ListSerializer(UnreadStage.serializer()), value)

    /** Server waits of this session; a new session namespace starts without them. */
    var cooldowns: List<ServerCooldowns.Cooldown>
        get() = decoded("cooldowns", ListSerializer(ServerCooldowns.Cooldown.serializer())) ?: emptyList()
        set(value) = encoded("cooldowns", ListSerializer(ServerCooldowns.Cooldown.serializer()), value)

    /** The last store state of each subscription, so only a change is reported to automations. */
    var subscriptionStates: Map<String, String>
        get() = decoded("subscriptionStates", stringMap) ?: emptyMap()
        set(value) = encoded("subscriptionStates", stringMap, value)

    var lastLimitCheck: Long?
        get() = read { if (contains(key("lastLimitCheck"))) getLong(key("lastLimitCheck"), 0) else null }
        set(value) = write { if (value == null) remove(key("lastLimitCheck")) else putLong(key("lastLimitCheck"), value) }

    var pendingActionClicks: List<String>
        get() = decoded("pendingActionClicks", ListSerializer(String.serializer())) ?: emptyList()
        set(value) {
            if (acceptsDrafts) encoded("pendingActionClicks", ListSerializer(String.serializer()), value)
        }

    /** Link tap results by message ID, beside the queue so its stored format stays a plain list. */
    var actionClickOutcomes: Map<String, String>
        get() = decoded("actionClickOutcomes", stringMap) ?: emptyMap()
        set(value) {
            if (acceptsDrafts) encoded("actionClickOutcomes", stringMap, value)
        }

    var draftText: String
        get() = read { getString(key("draftText"), null) } ?: ""
        set(value) {
            if (acceptsDrafts) write { putString(key("draftText"), value) }
        }

    var cursor: Long
        get() = read { getString(key("cursor"), null) }?.toLongOrNull() ?: 0
        set(value) = write { putString(key("cursor"), value.toString()) }

    var pending: DeviceIdentity
        get() = decoded("pending", DeviceIdentity.serializer()) ?: DeviceIdentity()
        set(value) = encoded("pending", DeviceIdentity.serializer(), value)

    /** Storage thread. Throws when secure storage cannot be read: an unreadable session is not a missing one. */
    fun loadCredentials(): SessionCredentials? = when (val result = credentialStorage.read(key("session"))) {
        SecureStore.ReadResult.Missing -> null
        SecureStore.ReadResult.Unavailable -> throw ReceptionAPIError("secure_storage_unavailable", 0)
        is SecureStore.ReadResult.Value -> try {
            ReceptionAPI.json.decodeFromString(SessionCredentials.serializer(), result.value)
        } catch (_: SerializationException) {
            throw ReceptionAPIError("secure_storage_unavailable", 0)
        } catch (_: IllegalArgumentException) {
            throw ReceptionAPIError("secure_storage_unavailable", 0)
        }
    }

    /** Storage thread. */
    val credentialsMissing: Boolean
        get() = credentialStorage.read(key("session")) == SecureStore.ReadResult.Missing

    /** Storage thread. Throws unless the credentials reached the disk. */
    fun save(credentials: SessionCredentials) {
        val value = ReceptionAPI.json.encodeToString(SessionCredentials.serializer(), credentials)
        if (!credentialStorage.save(value, key("session"))) throw ReceptionAPIError("secure_storage_unavailable", 0)
    }

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
        credentialStorage.delete(key("session"))
        clearChatFiles()
        remove(CHAT_KEYS + listOf("chatRevision", "pending", "cooldowns", "lastLimitCheck", "subscriptionStates"))
        generation(context, namespace, rotate = true)
    }

    private fun key(name: String): String = "$scope.$name"

    private fun remove(names: List<String>) = write { names.forEach { remove(key(it)) } }

    private fun <T> read(block: SharedPreferences.() -> T): T? = try {
        defaults.block()
    } catch (_: ClassCastException) {
        null
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        defaults.edit(action = block)
    }

    private fun <T> decoded(name: String, serializer: KSerializer<T>): T? {
        val text = read { getString(key(name), null) } ?: return null
        return try {
            ReceptionAPI.json.decodeFromString(serializer, text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun <T> encoded(name: String, serializer: KSerializer<T>, value: T?) {
        if (value == null) return write { remove(key(name)) }
        write { putString(key(name), ReceptionAPI.json.encodeToString(serializer, value)) }
    }

    companion object {
        private const val PREFERENCES = "sh.reception.sdk"
        private val stringMap = MapSerializer(String.serializer(), String.serializer())
        private val CHAT_KEYS = listOf(
            "cursor", "draftText", "hasConversation", "hasStartedChat", "conversationStatus", "lastMessageAt",
            "pushActive", "unreadPolling", "pendingActionClicks", "actionClickOutcomes",
        )

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

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
