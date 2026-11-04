package sh.reception.client

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The latest FCM message IDs the SDK handled, so a message delivered twice (FCM redelivers after a crash or a slow
 * service) shows one notification and refreshes the unread count once. FCM message IDs are globally unique, so one
 * list serves every App ID and the time before configure; it lives outside the session namespace, so logout and
 * deletion do not forget them. Storage thread only.
 */
internal object PushMessageIds {
    const val DATA_KEY: String = "google.message_id"
    const val CAPACITY: Int = 64
    private const val PREFERENCES = "sh.reception.push"
    private const val KEY = "messageIds"
    private val serializer = ListSerializer(String.serializer())

    /** Records [id]; false when it was recorded already. Unreadable storage counts as new. */
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
        preferences.edit { putString(KEY, ReceptionAPI.json.encodeToString(serializer, (ids + id).takeLast(CAPACITY))) }
        return true
    }
}
