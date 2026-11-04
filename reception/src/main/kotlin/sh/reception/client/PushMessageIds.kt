package sh.reception.client

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import sh.reception.Log

/**
 * The latest FCM message IDs the SDK handled, so a message delivered twice (FCM redelivers after a crash or a slow
 * service) shows one notification and refreshes the unread count once. FCM message IDs are globally unique, so one
 * list serves every App ID and the time before configure; it lives outside the session namespace, so logout and
 * deletion do not forget them. Storage thread only.
 */
internal object PushMessageIds {
    const val DATA_KEY: String = "google.message_id"
    const val CAPACITY: Int = 64
    /** Also holds `NotificationSmallIcon`, which shares its lifetime outside any session. */
    const val PREFERENCES: String = "sh.reception.push"
    private const val KEY = "messageIds"
    private val serializer = ListSerializer(String.serializer())

    /**
     * Records [id] on disk before answering, so the notification it allows is never posted while a crash could still
     * lose the record; false when it was recorded already. Unreadable storage counts as new; a failed write is logged
     * and the message is still handled, once in this process.
     */
    // The KTX `edit` drops the commit's result, which decides the log.
    @SuppressLint("UseKtx")
    fun record(context: Context, id: String): Boolean {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val ids = try {
            preferences.getString(KEY, null)?.let { ReceptionAPI.json.decodeFromString(serializer, it) }.orEmpty()
        } catch (_: ClassCastException) {
            emptyList()
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        if (id in ids) return false
        val encoded = ReceptionAPI.json.encodeToString(serializer, (ids + id).takeLast(CAPACITY))
        if (!preferences.edit().putString(KEY, encoded).commit()) {
            Log.error { "Push message ID not saved, a redelivery after a restart may notify again" }
        }
        return true
    }
}
