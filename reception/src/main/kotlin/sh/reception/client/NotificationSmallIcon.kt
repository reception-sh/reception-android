package sh.reception.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import sh.reception.Log
import sh.reception.R

/**
 * `Reception.notificationSmallIcon`, kept across process death: FCM can start the process for a message before (or
 * without) the code that sets the icon, as in Flutter and React Native apps, whose configure runs in Dart or JS. The
 * icon is stored by its resource name, which survives app updates that renumber resources, beside the push message
 * IDs and outside the session namespace. Main thread only; disk work on the storage thread.
 */
// Holds only the application context, which lives as long as the process.
@SuppressLint("StaticFieldLeak")
internal object NotificationSmallIcon {
    private const val KEY = "notificationSmallIcon"

    /** The icon the app set in this process, or the one read back; null for the built-in glyph. */
    var current: Int? = null
        private set

    /** True once [current] is authoritative: the app set it in this process, or the stored one was read. */
    private var known = false
    private var unsaved = false
    private var context: Context? = null

    fun set(icon: Int?) {
        current = icon
        known = true
        unsaved = true
        context?.let(::save)
    }

    /** Called with the first context the SDK sees (configure, a push message), so an icon set before it is stored. */
    fun attach(context: Context) {
        val application = this.context ?: context.applicationContext.also { this.context = it }
        if (unsaved) save(application)
    }

    /** The icon for a notification posted now; reads the stored one when the app has not set one in this process. */
    suspend fun resolved(context: Context): Int {
        attach(context)
        if (!known) {
            val application = context.applicationContext
            val restored = Storage.run { stored(application) }
            // The app may have set an icon while the read ran; that one wins.
            if (!known) {
                current = restored
                known = true
            }
        }
        return current ?: R.drawable.reception_symbol_notifications
    }

    private fun save(context: Context) {
        unsaved = false
        val icon = current
        Storage.launch {
            val name = icon?.let { context.resources.nameOf(it) }
            preferences(context).edit { if (name == null) remove(KEY) else putString(KEY, name) }
        }
    }

    private fun Resources.nameOf(icon: Int): String? = try {
        getResourceName(icon)
    } catch (_: Resources.NotFoundException) {
        Log.error { "notificationSmallIcon is not a resource of this app, not stored" }
        null
    }

    /** An entry name that no longer resolves (the app removed the drawable) falls back to the built-in glyph. */
    // The resource ID itself is not stable across app updates, so the name is looked up.
    @SuppressLint("DiscouragedApi")
    private fun stored(context: Context): Int? {
        val name = try {
            preferences(context).getString(KEY, null)
        } catch (_: ClassCastException) {
            null
        } ?: return null
        return context.resources.getIdentifier(name, null, null).takeIf { it != 0 }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PushMessageIds.PREFERENCES, Context.MODE_PRIVATE)

    @VisibleForTesting
    fun resetForTests() {
        current = null
        known = false
        unsaved = false
        context = null
    }
}
