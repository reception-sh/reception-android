package sh.reception.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import sh.reception.Log

/** The chat sheet's launch intent: which presentation it belongs to and how it appears. Main thread only. */
internal object SheetLaunch {
    /** The launch intent's extra that names the presentation a sheet belongs to. */
    const val EXTRA_PRESENTATION = "sh.reception.presentation"

    /** Set on a sheet that comes back after a launcher return finished it ([ReturningSheet]): it shows open, without its entry. */
    const val EXTRA_RETURNING = "sh.reception.returning"

    /** Starts the sheet of presentation [token] over [host]; false when the merged manifest lacks it. */
    fun start(host: Activity, token: String, returning: Boolean): Boolean = try {
        host.startActivity(Intent(host, ReceptionChatActivity::class.java).putExtra(EXTRA_PRESENTATION, token)
            .putExtra(EXTRA_RETURNING, returning))
        true
    } catch (_: ActivityNotFoundException) {
        Log.error { "Chat presentation failed, ReceptionChatActivity missing from the merged manifest" }
        false
    }
}
