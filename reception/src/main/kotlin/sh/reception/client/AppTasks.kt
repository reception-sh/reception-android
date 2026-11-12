package sh.reception.client

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** The app's own tasks (`ActivityManager.appTasks`), which it may bring to the front without a permission. */
internal object AppTasks {
    /** The live task with id [taskId]. */
    fun withId(context: Context, taskId: Int): ActivityManager.AppTask? =
        find(context) { info -> id(info) == taskId && info.numActivities > 0 }

    /**
     * A task of the app other than [excluding] that `moveToFront()` restores: one with live activities, or a card kept
     * in Recents after its process died, which has no activity records until it is restored. Its root must be one of
     * the app's activities other than [trampoline], whose own leftover tasks hold nothing to restore.
     */
    fun restorable(context: Context, excluding: Int, trampoline: String): ActivityManager.AppTask? =
        find(context) { info ->
            val root = info.baseIntent?.component
            id(info) != excluding && root?.packageName == context.packageName && root.className != trampoline
        }

    private fun find(context: Context, matches: (ActivityManager.RecentTaskInfo) -> Boolean): ActivityManager.AppTask? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return manager.appTasks.firstOrNull { task ->
            try {
                matches(task.taskInfo)
            } catch (_: IllegalArgumentException) {
                // The task was removed since the list was read.
                false
            }
        }
    }

    private fun id(info: ActivityManager.RecentTaskInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else @Suppress("DEPRECATION") info.persistentId
}
