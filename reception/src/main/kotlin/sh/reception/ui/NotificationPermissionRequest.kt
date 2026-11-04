package sh.reception.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import sh.reception.viewmodel.ChatModel
import sh.reception.viewmodel.notificationPermissionAnswered
import sh.reception.viewmodel.refreshNotificationAuthorization

/**
 * The notification card's system dialog, through the activity hosting the chat: the SDK's sheet, or the app's own
 * activity when it embeds the chat and has an activity result registry. Returns null when the chat cannot ask; the
 * card then never shows. The authorization is read when the chat appears and whenever its activity resumes.
 */
@Composable
internal fun rememberNotificationPermission(model: ChatModel): (() -> Unit)? {
    val activity = LocalContext.current.findActivity()
    val launcher = if (Build.VERSION.SDK_INT >= 33 && activity != null && LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            model.notificationPermissionAnswered(granted, activity)
        }
    } else {
        null
    }
    val owner = LocalLifecycleOwner.current
    val canRequest = launcher != null
    DisposableEffect(owner, model, canRequest) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                model.refreshNotificationAuthorization(activity, canRequest)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return launcher?.let { request ->
        { if (Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
