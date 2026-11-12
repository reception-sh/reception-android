package sh.reception.receptionAndroidExample

import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import sh.reception.ReceptionChatView

/**
 * The Compose `Dialog` configurations a host can embed the chat in: the default, full width
 * (`usePlatformDefaultWidth = false`), edge to edge (`decorFitsSystemWindows = false`) and both. The composer must sit
 * on the keyboard in each. Above the chat, a host text field (focus moving away from the composer and back) and a
 * button with which the host sets its own `adjustPan` on the dialog's window while the chat is in it.
 */
private enum class ChatDialog(@StringRes val label: Int, val properties: DialogProperties) {
    Default(R.string.chat_in_dialog, DialogProperties()),
    FullWidth(R.string.chat_in_full_width_dialog, DialogProperties(usePlatformDefaultWidth = false)),
    EdgeToEdge(R.string.chat_in_edge_to_edge_dialog, DialogProperties(decorFitsSystemWindows = false)),
    FullWidthEdgeToEdge(
        R.string.chat_in_full_width_edge_to_edge_dialog,
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ),
}

@Composable
fun ChatDialogs() {
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    ChatDialog.entries.forEach { dialog ->
        OutlinedButton(onClick = { shown = dialog.name }) { Text(stringResource(dialog.label)) }
    }
    val dialog = ChatDialog.entries.firstOrNull { it.name == shown } ?: return
    Dialog(onDismissRequest = { shown = null }, properties = dialog.properties) {
        Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
            Column {
                HostControls()
                ReceptionChatView(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HostControls() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    var text by rememberSaveable { mutableStateOf("") }
    var panned by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.host_field)) })
        TextButton(enabled = !panned && window != null, onClick = {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            panned = true
        }) { Text(stringResource(if (panned) R.string.host_panned else R.string.host_sets_pan)) }
    }
}
