package sh.reception.client

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit

/**
 * The SDK sheet's latest accepted presentation, so the sheet that Android restores after the process died is shown
 * again while a sheet of a closed, cancelled or superseded presentation is not. Kept with the storage namespace it
 * showed, outside the session namespace (the record outlives the process, not the configuration). Storage thread only.
 */
internal object PresentationRecord {
    private const val PREFERENCES = "sh.reception.presentation"
    private const val TOKEN = "token"
    private const val NAMESPACE = "namespace"

    /** The presentation's token and namespace. */
    data class Entry(val token: String, val namespace: String)

    /** Commits [entry]; false when it did not reach the disk. */
    // The KTX `edit` drops the commit's result, which decides whether the sheet can be restored.
    @SuppressLint("UseKtx")
    fun save(context: Context, entry: Entry): Boolean =
        preferences(context).edit().putString(TOKEN, entry.token).putString(NAMESPACE, entry.namespace).commit()

    fun clear(context: Context) {
        preferences(context).edit(commit = true) { clear() }
    }

    /** The recorded presentation; a value of the wrong type reads as none. */
    fun load(context: Context): Entry? = try {
        val preferences = preferences(context)
        val token = preferences.getString(TOKEN, null)
        val namespace = preferences.getString(NAMESPACE, null)
        if (token != null && namespace != null) Entry(token, namespace) else null
    } catch (_: ClassCastException) {
        null
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
