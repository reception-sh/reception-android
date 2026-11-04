package sh.reception.configuration

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.SerializationException
import sh.reception.client.CacheFiles
import sh.reception.client.MAXIMUM_APPEARANCE_BYTES
import sh.reception.client.ReceptionAPI

/** The last compatible envelope of one App ID and its ETag, independent of the device session. */
internal class AppearanceCache(context: Context, scope: String) {
    class Snapshot(val envelope: AppearanceEnvelope, val etag: String?)

    private val key = CacheFiles.hash(scope)
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The ETag is used only together with the envelope it belongs to. */
    fun load(): Snapshot? {
        val (body, etag) = try {
            (preferences.getString(key, null) ?: return null) to preferences.getString("$key.etag", null)
        } catch (_: ClassCastException) {
            return null
        }
        val envelope = decode(body) ?: return null
        return Snapshot(envelope, etag)
    }

    fun save(body: String, etag: String?) {
        if (body.toByteArray().size > MAXIMUM_APPEARANCE_BYTES) return
        preferences.edit {
            putString(key, body)
            if (etag != null) putString("$key.etag", etag) else remove("$key.etag")
        }
    }

    companion object {
        private const val PREFERENCES = "sh.reception.appearance"

        /** Null for an oversized or undecodable body, and for an incompatible one when [requireCompatible]. */
        fun decode(body: String, requireCompatible: Boolean = true): AppearanceEnvelope? {
            if (body.toByteArray().size > MAXIMUM_APPEARANCE_BYTES) return null
            val envelope = try {
                ReceptionAPI.json.decodeFromString(AppearanceEnvelope.serializer(), body)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            return envelope.takeIf { it.isCompatible || !requireCompatible }
        }
    }
}
