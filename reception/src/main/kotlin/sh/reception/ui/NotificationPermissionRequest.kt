package sh.reception.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
 *
 * The activity is looked up from the composition's context each time it is needed, never kept: a host may rebind its
 * context wrapper to a new activity (Flutter's platform views use a `MutableContextWrapper`), and the old one is
 * destroyed.
 */
@Composable
internal fun rememberNotificationPermission(model: ChatModel): (() -> Unit)? {
    val context by rememberUpdatedState(LocalContext.current)
    val hasActivity = context.findActivity() != null
    val launcher = if (Build.VERSION.SDK_INT >= 33 && hasActivity && LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            model.notificationPermissionAnswered(granted, context.findActivity())
        }
    } else {
        null
    }
    val owner = LocalLifecycleOwner.current
    val canRequest = launcher != null
    DisposableEffect(owner, model, canRequest) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                model.refreshNotificationAuthorization(context.findActivity(), canRequest)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return launcher?.let { request ->
        { if (Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
