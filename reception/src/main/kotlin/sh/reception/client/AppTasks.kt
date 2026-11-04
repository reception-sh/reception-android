package sh.reception.client

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** The app's own tasks (`ActivityManager.appTasks`), which it may bring to the front without a permission. */
internal object AppTasks {
    /** The first task with activities that [matches] its id. */
    fun find(context: Context, matches: (Int) -> Boolean): ActivityManager.AppTask? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return manager.appTasks.firstOrNull { task ->
            try {
                val info = task.taskInfo
                matches(id(info)) && info.numActivities > 0
            } catch (_: IllegalArgumentException) {
                // The task was removed since the list was read.
                false
            }
        }
    }

    private fun id(info: ActivityManager.RecentTaskInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else @Suppress("DEPRECATION") info.persistentId
}
