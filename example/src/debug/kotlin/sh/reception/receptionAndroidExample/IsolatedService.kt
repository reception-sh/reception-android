package sh.reception.receptionAndroidExample

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import sh.reception.Reception

/**
 * Debug builds only: a service in an isolated process (`android:isolatedProcess="true"`), as a host's sandboxed
 * renderer would be. The example's `Application.onCreate` configures here too; the SDK refuses it with one error line
 * and must not crash (before Android 9 the process list it once asked throws `SecurityException` here).
 */
class IsolatedService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val claimed = Reception.handlePushMessage(this, mapOf("reception" to "isolated", "title" to "Team", "body" to "Hi"))
        Log.i("ReceptionExample", "Isolated process: handlePushMessage returned $claimed")
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
