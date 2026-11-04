package sh.reception.receptionAndroidExample

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import sh.reception.Reception
import sh.reception.ReceptionChatView

/**
 * Debug builds only: host setups the SDK must cope with. `adb shell am start … --ez adjustNothing true` gives the
 * activity `adjustNothing` (the embedded chat's keyboard before Android 11), "Chat in a dialog" embeds the chat in a
 * Compose `Dialog` with its own window, and the icon switch sets `Reception.notificationSmallIcon`, which a process
 * that FCM starts later must still use.
 */
object ExampleDebugTools {
    const val EXTRA_ADJUST_NOTHING = "adjustNothing"

    fun applyWindowMode(activity: Activity) {
        if (activity.intent.getBooleanExtra(EXTRA_ADJUST_NOTHING, false)) {
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }
}

@Composable
fun DebugTools() {
    var dialog by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { dialog = true }) { Text(stringResource(R.string.chat_in_dialog)) }
    if (dialog) {
        // A platform-sized dialog: its window resizes above the keyboard, and the chat takes the height it gets.
        Dialog(onDismissRequest = { dialog = false }) {
            Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
                ReceptionChatView()
            }
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
