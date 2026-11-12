package sh.reception.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import sh.reception.Log
import sh.reception.client.AppTasks

/**
 * Brings back a sheet that another of the app's activities covers. `FLAG_ACTIVITY_REORDER_TO_FRONT` reorders only
 * inside the task of the activity that starts it, so a sheet in another task of the app first gets its task moved to
 * the front, and the activity that then resumes in that task (the one covering the sheet, if any) reorders it. Main
 * thread only.
 */
internal object CoveredSheet {
    /**
     * The task whose next resumed activity reorders the sheet. Kept through resumes in other tasks (another task's
     * activity, or a plugin handing its activity over again before the moved task resumes); cleared once the task
     * resumed, or when the presentation ends.
     */
    private var reorderingTask: Int? = null

    /** Answers whether the sheet was brought to the front or will be once its task resumes. */
    fun bringToFront(host: Activity, covered: ReceptionChatActivity): Boolean {
        reorderingTask = null
        if (covered.taskId == host.taskId) {
            covered.keepKeyboardState()
            if (!reorder(host)) return false
            Log.debug { "Chat brought to the front" }
            return true
        }
        val task = AppTasks.withId(host, covered.taskId) ?: return false
        covered.keepKeyboardState()
        reorderingTask = covered.taskId
        task.moveToFront()
        Log.debug { "Chat brought to the front with its task" }
        return true
    }

    /** An activity resumed; in the task moved to the front for [sheet], it reorders the sheet above itself. */
    fun activityResumed(activity: Activity, sheet: ReceptionChatActivity?) {
        val task = reorderingTask ?: return
        if (sheet?.taskId != task) {
            reorderingTask = null
            return
        }
        if (activity.taskId != task) return
        reorderingTask = null
        // The sheet itself on top of its task needs no reorder.
        if (activity is ReceptionChatActivity) return
        if (reorder(activity)) Log.debug { "Chat brought to the front within its task" }
    }

    /** The moved task is no longer awaited: the chat was dismissed, reset or its sheet ended. */
    fun cancel() {
        reorderingTask = null
    }

    private fun reorder(from: Activity): Boolean = try {
        from.startActivity(Intent(from, ReceptionChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        true
    } catch (_: ActivityNotFoundException) {
        Log.error { "Chat presentation failed, ReceptionChatActivity missing from the merged manifest" }
        false
    }
}
