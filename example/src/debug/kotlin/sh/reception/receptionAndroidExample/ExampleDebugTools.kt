package sh.reception.receptionAndroidExample

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.reception.Reception
import sh.reception.ReceptionChatView

/**
 * Debug builds only (release builds have no-op hooks): host setups the SDK must cope with. `adb shell am start …
 * --ez adjustNothing true` gives the activity `adjustNothing` (the embedded chat's keyboard before Android 11), the
 * "Chat in a … dialog" buttons embed the chat in a Compose `Dialog` with its own window, in each configuration
 * ([ChatDialogs]), "Chat above a bottom bar" in a tab above a
 * navigation bar ([TabsChatActivity]), "Chat in a bottom sheet" in a Material 3 `ModalBottomSheet`, "Scaled chat" in a
 * container the host scales ([ScaledChatActivity]), "Chat moved into a dialog" moves a retained chat view between
 * windows ([MovingChatActivity]), "singleTask launcher activity" is a launcher activity like Expo's
 * ([SingleTaskActivity]), "Second process" an activity and a service in another process ([OtherProcessActivity]), and
 * the icon switch sets `Reception.notificationSmallIcon`, which a process that FCM
 * starts later must still use.
 */
object ExampleDebugTools {
    const val EXTRA_ADJUST_NOTHING = "adjustNothing"

    fun applyWindowMode(activity: Activity) {
        if (activity.intent.getBooleanExtra(EXTRA_ADJUST_NOTHING, false)) {
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugTools() {
    val context = LocalContext.current
    OutlinedButton(onClick = { context.startActivity(Intent(context, TabsChatActivity::class.java)) }) {
        Text(stringResource(R.string.chat_above_bottom_bar))
    }
    OutlinedButton(onClick = { context.startActivity(Intent(context, ScaledChatActivity::class.java)) }) {
        Text(stringResource(R.string.scaled_chat))
    }
    OutlinedButton(onClick = { context.startActivity(Intent(context, MovingChatActivity::class.java)) }) {
        Text(stringResource(R.string.chat_moved_into_dialog))
    }
    OutlinedButton(onClick = { context.startActivity(Intent(context, SingleTaskActivity::class.java)) }) {
        Text(stringResource(R.string.single_task_activity))
    }
    OutlinedButton(onClick = { context.startActivity(Intent(context, OtherProcessActivity::class.java)) }) {
        Text(stringResource(R.string.other_process))
    }
    ChatDialogs()
    var sheet by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { sheet = true }) { Text(stringResource(R.string.chat_in_bottom_sheet)) }
    if (sheet) {
        // Fully expanded, so the composer is on screen. Material 3 1.5 deprecates the state function for one 1.3 lacks;
        // the release check compiles this host code against both.
        @Suppress("DEPRECATION")
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = state) {
            ReceptionChatView(Modifier.fillMaxHeight(0.8f))
        }
    }
    var icon by rememberSaveable { mutableStateOf(Reception.notificationSmallIcon != null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_notification_icon))
        Switch(checked = icon, onCheckedChange = {
            icon = it
            Reception.notificationSmallIcon = if (it) R.drawable.ic_example_notification else null
        })
    }
}
