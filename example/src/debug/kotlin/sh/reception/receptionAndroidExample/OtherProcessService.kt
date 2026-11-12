package sh.reception.receptionAndroidExample

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import sh.reception.Reception

/**
 * Debug builds only: a service in the second process (`:other`), as a host's messaging service with `android:process`
 * would be. It hands one Reception payload to `handlePushMessage`, which claims it without showing anything there.
 */
class OtherProcessService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = mapOf("reception" to "other-process", "title" to "Team", "body" to "Hello")
        val claimed = Reception.handlePushMessage(this, data, null)
        Log.i("ReceptionExample", "Other process: handlePushMessage returned $claimed")
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
