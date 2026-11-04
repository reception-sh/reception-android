package sh.reception.client

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** iOS `UNAuthorizationStatus` as far as the notification card needs it. */
internal enum class NotificationAuthorization {
    /** The system has not asked yet and can ask now: the card may offer it. */
    NOT_DETERMINED,
    DENIED,
    AUTHORIZED,
}

/**
 * Android's answer to "has the system asked?" (SPEC.md, notification card). Android 13+ asks for `POST_NOTIFICATIONS`;
 * it has not been answered while it is not granted, the rationale flag is off and no answer was stored. Below 13 the
 * only way to enable notifications is the system settings, so nothing is ever "not determined".
 */
internal object NotificationPermission {
    fun resolve(
        enabled: Boolean,
        sdk: Int,
        granted: Boolean,
        answered: Boolean,
        rationale: Boolean,
        canRequest: Boolean,
    ): NotificationAuthorization = when {
        enabled -> NotificationAuthorization.AUTHORIZED
        // Granted but off: the person turned notifications off in the settings.
        sdk < 33 || granted || answered || rationale || !canRequest -> NotificationAuthorization.DENIED
        else -> NotificationAuthorization.NOT_DETERMINED
    }

    /** [activity] asks; without one (or without a result registry, [canRequest] false) the card cannot. */
    fun read(context: Context, activity: Activity?, canRequest: Boolean, answered: Boolean): NotificationAuthorization {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < 33) {
            return resolve(enabled, Build.VERSION.SDK_INT, granted = enabled, answered, rationale = false, canRequest)
        }
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val rationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true
        return resolve(enabled, Build.VERSION.SDK_INT, granted, answered, rationale, canRequest && activity != null)
    }
}
