package sh.reception.receptionAndroidExample

import android.app.Application
import android.os.StrictMode
import android.util.Log
import sh.reception.Reception
import sh.reception.ReceptionInternalApi

class ExampleApplication : Application() {
    @OptIn(ReceptionInternalApi::class)
    override fun onCreate() {
        super.onCreate()
        // Debug builds die on main-thread disk access, so the SDK's storage work is checked on every run.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().penaltyDeath().build(),
            )
        }
        Reception.logLevel = Reception.LogLevel.DEBUG
        val appId = BuildConfig.RECEPTION_APP_ID
        if (appId.isEmpty()) {
            Log.w(TAG, "No App ID in .local/app-id.txt, Reception is not configured")
            return
        }
        if (BuildConfig.DEBUG) {
            Reception.configureForTesting(this, appId, BuildConfig.RECEPTION_SERVICE_URL)
        } else {
            Reception.configure(this, appId)
        }
    }

    private companion object {
        const val TAG = "ReceptionExample"
    }
}
