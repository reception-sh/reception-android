package sh.reception.receptionAndroidExample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.reception.Reception
import sh.reception.ReceptionChatView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // A host theme of its own, so the embedded chat shows how it follows the app's colors.
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8AB4FF))
            else lightColorScheme(primary = Color(0xFF1F6FEB))
            MaterialTheme(colorScheme = scheme) {
                var embedded by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = embedded) { embedded = false }
                if (embedded) EmbeddedScreen(close = { embedded = false }) else HomeScreen(embed = { embedded = true })
                Notice()
            }
        }
    }
}

@Composable
private fun HomeScreen(embed: () -> Unit) {
    Scaffold { padding ->
        Column(
            // Scrolls on small screens and with large fonts.
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val unread by Reception.unreadCount.collectAsState()
            BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
                Button(onClick = { Reception.openChat() }) { Text(stringResource(R.string.support)) }
            }
            OutlinedButton(onClick = embed) { Text(stringResource(R.string.embedded_chat)) }
            val context = LocalContext.current
            OutlinedButton(onClick = { context.startActivity(Intent(context, SecondActivity::class.java)) }) {
                Text(stringResource(R.string.second_screen))
            }
            OutlinedButton(onClick = { Reception.identify(userId = "android-example-user", name = "Android Example") }) {
                Text(stringResource(R.string.identify))
            }
            OutlinedButton(onClick = { Reception.logout() }) { Text(stringResource(R.string.logout)) }
            Actions()
            Settings()
            Text(stringResource(R.string.unread_count, unread))
            Text(stringResource(R.string.sdk_version, Reception.version), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Push, automations, deletion and a verified user switch, two buttons a row. */
@Composable
private fun Actions() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = ExampleActions::registerForPush) { Text(stringResource(R.string.register_push)) }
        OutlinedButton(onClick = ExampleActions::track) { Text(stringResource(R.string.track_event)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = ExampleActions::deleteData) { Text(stringResource(R.string.delete_data)) }
        OutlinedButton(onClick = ExampleActions::switchUser) { Text(stringResource(R.string.switch_user)) }
    }
}

/** The language override and the chat's color scheme, for checking both in the open chat. */
@Composable
private fun Settings() {
    var german by rememberSaveable { mutableStateOf(Reception.languageOverride == "de") }
    var scheme by rememberSaveable { mutableStateOf(Reception.appearance.preferredColorScheme) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.language_override))
        Switch(checked = german, onCheckedChange = {
            german = it
            Reception.languageOverride = if (it) "de" else null
        })
    }
    var badge by rememberSaveable { mutableStateOf(Reception.updatesAppBadge) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.updates_app_badge))
        Switch(checked = badge, onCheckedChange = {
            badge = it
            Reception.updatesAppBadge = it
        })
    }
    OutlinedButton(onClick = {
        scheme = when (scheme) {
            null -> Reception.ColorScheme.LIGHT
            Reception.ColorScheme.LIGHT -> Reception.ColorScheme.DARK
            Reception.ColorScheme.DARK -> null
        }
        Reception.appearance.preferredColorScheme = scheme
    }) {
        Text(stringResource(R.string.chat_appearance, when (scheme) {
            null -> stringResource(R.string.appearance_system)
            Reception.ColorScheme.LIGHT -> stringResource(R.string.appearance_light)
            Reception.ColorScheme.DARK -> stringResource(R.string.appearance_dark)
        }))
    }
}

/** What a paywall or screen card asked the app to show. */
@Composable
private fun Notice() {
    val notice = ExampleFeatures.notice ?: return
    AlertDialog(
        onDismissRequest = { ExampleFeatures.notice = null },
        confirmButton = { TextButton(onClick = { ExampleFeatures.notice = null }) { Text(stringResource(R.string.ok)) } },
        text = { Text(notice) },
    )
}

/** The chat as a screen of the app's own navigation, under the app's theme. */
@Composable
private fun EmbeddedScreen(close: () -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            TextButton(onClick = close, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.back)) }
            ReceptionChatView(Modifier.weight(1f))
        }
    }
}
