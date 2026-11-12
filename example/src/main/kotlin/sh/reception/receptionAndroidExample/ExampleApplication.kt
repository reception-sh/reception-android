package sh.reception.receptionAndroidExample

import android.app.Application
import android.os.StrictMode
import android.util.Log
import androidx.compose.ui.graphics.Color
import sh.reception.Reception
import sh.reception.ReceptionColor
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
        Reception.onEvent = { event -> Log.i(TAG, "Event $event") }
        // Adaptive colors: a light and dark pair, and a color resource with a night variant.
        Reception.appearance.accentColor = ReceptionColor(light = Color(0xFF5B4FE0), dark = Color(0xFFB0A8FF))
        Reception.appearance.welcomeIconColor = ReceptionColor.resource(R.color.example_welcome_icon)
        val appId = BuildConfig.RECEPTION_APP_ID
        if (appId.isEmpty()) {
            Log.w(TAG, "No App ID in .local/app-id.txt, Reception is not configured")
            return
        }
        ExampleFeatures.register()
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
