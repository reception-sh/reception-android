package sh.reception.receptionAndroidExample

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import sh.reception.Reception
import sh.reception.ReceptionInternalApi

/**
 * Debug builds only: lets verification drive what a plugin or FCM would, with `adb shell am broadcast`.
 * `…PUSH --es reception <id> --es title … --es body … --es google.message_id <id>` hands the extras to
 * `handlePushMessage`; `…ROUTING --ez enabled false` is a plugin's `configure(handlesNotifications: false)`;
 * `…COVER` starts the second screen inside the chat sheet's task, above the sheet (`--ez singleTask true`: the
 * singleTask task's covering screen, [SingleTaskCoverActivity]); `…LOGOUT` logs out (also in a process the broadcast
 * started); `…IDENTIFY --es token <JWT>` stages an identity token minted outside the app; `…TRACK` tracks the
 * example's event (`--es event <name> --ez opensChat <bool>`: another one); `…CLOSE` calls `closeChat()`;
 * `…CLOSE_BUTTON --ez shown true` sets the appearance's close button, `…TITLE --es title <text>` its title (no extra:
 * the default); `…ISOLATED` starts [IsolatedService];
 * `…CLEAR_TOP` is the host's own navigation to [SingleTaskActivity] with `FLAG_ACTIVITY_CLEAR_TOP`, `…LAUNCH_INTENT`
 * its start of that activity with a launch intent (`ACTION_MAIN`, `CATEGORY_LAUNCHER`).
 */
class VerificationReceiver : BroadcastReceiver() {
    @OptIn(ReceptionInternalApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PUSH -> {
                val extras = intent.extras ?: return
                val data = extras.keySet().associateWith { extras.getString(it).orEmpty() }
                Log.i(TAG, "handlePushMessage returned ${Reception.handlePushMessage(context, data)}")
            }
            ACTION_ROUTING -> Reception.setNotificationRoutingEnabled(intent.getBooleanExtra("enabled", true))
            ACTION_COVER -> {
                val tasks = context.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
                val sheetTask = tasks.firstOrNull { it.taskInfo.topActivity?.className == SHEET }
                val screen = if (intent.getBooleanExtra("singleTask", false)) SingleTaskCoverActivity::class.java else SecondActivity::class.java
                sheetTask?.startActivity(context, Intent(context, screen), null)
                Log.i(TAG, if (sheetTask == null) "No task with the chat on top" else "Second screen started above the chat")
            }
            ACTION_LOGOUT -> Reception.logout()
            ACTION_CLOSE -> Reception.closeChat()
            ACTION_CLOSE_BUTTON -> Reception.appearance.showsCloseButton = intent.getBooleanExtra("shown", true)
            ACTION_TITLE -> Reception.appearance.title = intent.getStringExtra("title") ?: Reception.Appearance().title
            ACTION_IDENTIFY -> intent.getStringExtra("token")?.let(Reception::identifyWithToken)
            ACTION_ISOLATED -> context.startService(Intent(context, IsolatedService::class.java))
            ACTION_CLEAR_TOP -> context.startActivity(
                Intent(context, SingleTaskActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            ACTION_LAUNCH_INTENT -> context.startActivity(SingleTaskActivity.launchIntent(context))
            ACTION_TRACK -> {
                val event = intent.getStringExtra("event")
                if (event == null) ExampleActions.track() else Reception.track(event, intent.getBooleanExtra("opensChat", false))
                val after = intent.getLongExtra("secondAfter", -1)
                if (after < 0) return
                val application = context.applicationContext
                val second = Intent(application, SecondActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Handler(Looper.getMainLooper()).postDelayed({ application.startActivity(second) }, after)
            }
        }
    }

    private companion object {
        const val TAG = "ReceptionExample"
        const val ACTION_PUSH = "sh.reception.receptionAndroidExample.PUSH"
        const val ACTION_ROUTING = "sh.reception.receptionAndroidExample.ROUTING"
        const val ACTION_COVER = "sh.reception.receptionAndroidExample.COVER"
        const val ACTION_LOGOUT = "sh.reception.receptionAndroidExample.LOGOUT"
        const val ACTION_CLOSE = "sh.reception.receptionAndroidExample.CLOSE"
        const val ACTION_CLOSE_BUTTON = "sh.reception.receptionAndroidExample.CLOSE_BUTTON"
        const val ACTION_TITLE = "sh.reception.receptionAndroidExample.TITLE"
        const val ACTION_IDENTIFY = "sh.reception.receptionAndroidExample.IDENTIFY"
        const val ACTION_TRACK = "sh.reception.receptionAndroidExample.TRACK"
        const val ACTION_ISOLATED = "sh.reception.receptionAndroidExample.ISOLATED"
        const val ACTION_CLEAR_TOP = "sh.reception.receptionAndroidExample.CLEAR_TOP"
        const val ACTION_LAUNCH_INTENT = "sh.reception.receptionAndroidExample.LAUNCH_INTENT"
        const val SHEET = "sh.reception.ui.ReceptionChatActivity"
    }
}
