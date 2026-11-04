package sh.reception.ui

import android.app.Activity
import android.content.Intent
import android.os.BadParcelableException
import android.os.Bundle
import sh.reception.Log
import sh.reception.ReceptionPush
import sh.reception.client.AppTasks
import sh.reception.client.PushNotifications

/**
 * The target of the SDK's notifications, never drawn: it records the tap and finishes. It shares the app's task, so
 * the tap already brought an existing task forward with its state, and finishing reveals its top activity. Only as
 * the root of a new task does it bring back another task of the app, or, when none exists, start the launcher intent, so
 * the app starts cold. The
 * chat then opens over whatever activity resumes, so taps need nothing from the host's activities.
 */
internal class ReceptionNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreation replays a tap that was handled already, and Recents replays the original intent.
        val replayed = savedInstanceState != null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (!replayed) tap()?.let { (key, conversationId) -> ReceptionPush.notificationTapped(key, conversationId) }
        // Starting the launcher intent inside an existing task would add another launcher activity when the task's root
        // intent differs from it, for example after the app was started by a deep link.
        if (!isTaskRoot) return finish()
        // A task of the app outlived its process (its card is still in Recents) but the tap started a new one: the
        // launcher activity declares another task affinity, like Flutter's `taskAffinity=""`. Starting the launcher
        // intent would open a second card; bringing that task back restores it.
        AppTasks.find(this) { it != taskId }?.let { task ->
            task.moveToFront()
            return finishAndRemoveTask()
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Log.error { "Notification tap not routed, the app has no launcher activity" }
        } else {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    /** The notification's key and conversation. */
    private fun tap(): Pair<String, String>? = try {
        val conversationId = intent.getStringExtra(PushNotifications.EXTRA_CONVERSATION)?.takeIf { it.isNotEmpty() }
        conversationId?.let { (intent.getStringExtra(PushNotifications.EXTRA_NOTIFICATION)?.takeIf { key -> key.isNotEmpty() } ?: it) to it }
    } catch (_: BadParcelableException) {
        null
    }
}
