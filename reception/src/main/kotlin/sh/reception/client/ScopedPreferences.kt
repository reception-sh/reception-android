package sh.reception.client

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * Typed values under one session scope's keys (`<scope>.<name>`) in a SharedPreferences file. Reads come from memory
 * once the file is loaded; writes reach the disk asynchronously. A value of the wrong type or format reads as missing.
 */
internal class ScopedPreferences(private val defaults: () -> SharedPreferences, private val scope: () -> String) {
    fun key(name: String): String = "${scope()}.$name"

    fun boolean(name: String): Boolean = read { getBoolean(key(name), false) } == true

    fun putBoolean(name: String, value: Boolean) = write { putBoolean(key(name), value) }

    fun optionalBoolean(name: String): Boolean? = read { if (contains(key(name))) getBoolean(key(name), false) else null }

    fun putOptionalBoolean(name: String, value: Boolean?) =
        write { if (value == null) remove(key(name)) else putBoolean(key(name), value) }

    fun int(name: String): Int = read { getInt(key(name), 0) } ?: 0

    fun putInt(name: String, value: Int) = write { putInt(key(name), value) }

    fun long(name: String): Long? = read { if (contains(key(name))) getLong(key(name), 0) else null }

    fun putLong(name: String, value: Long?) = write { if (value == null) remove(key(name)) else putLong(key(name), value) }

    fun string(name: String): String? = read { getString(key(name), null) }

    fun putString(name: String, value: String?) = write { putString(key(name), value) }

    /** A value outside the session scope, such as a per-App ID flag that outlives sessions. */
    fun unscopedBoolean(key: String): Boolean = read { getBoolean(key, false) } == true

    fun putUnscopedBoolean(key: String, value: Boolean) = write { putBoolean(key, value) }

    fun <T> decoded(name: String, serializer: KSerializer<T>): T? {
        val text = string(name) ?: return null
        return try {
            ReceptionAPI.json.decodeFromString(serializer, text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun <T> encoded(name: String, serializer: KSerializer<T>, value: T?) {
        if (value == null) return write { remove(key(name)) }
        write { putString(key(name), ReceptionAPI.json.encodeToString(serializer, value)) }
    }

    fun remove(names: List<String>) = write { names.forEach { remove(key(it)) } }

    private fun <T> read(block: SharedPreferences.() -> T): T? = try {
        defaults().block()
    } catch (_: ClassCastException) {
        null
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        defaults().edit(action = block)
    }
}
