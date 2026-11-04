package sh.reception.receptionAndroidExample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sh.reception.Reception
import sh.reception.ReceptionInternalApi

/**
 * Debug builds only: lets verification drive what a plugin or FCM would, with `adb shell am broadcast`.
 * `…PUSH --es reception <id> --es title … --es body … --es google.message_id <id>` hands the extras to
 * `handlePushMessage`; `…ROUTING --ez enabled false` is a plugin's `configure(handlesNotifications: false)`.
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
        }
    }

    private companion object {
        const val TAG = "ReceptionExample"
        const val ACTION_PUSH = "sh.reception.receptionAndroidExample.PUSH"
        const val ACTION_ROUTING = "sh.reception.receptionAndroidExample.ROUTING"
    }
}
