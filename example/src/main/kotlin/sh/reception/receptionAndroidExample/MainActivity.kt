package sh.reception.receptionAndroidExample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
            }
        }
    }
}

@Composable
private fun HomeScreen(embed: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val unread by Reception.unreadCount.collectAsState()
            BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
                Button(onClick = { Reception.openChat() }) { Text(stringResource(R.string.support)) }
            }
            OutlinedButton(onClick = embed) { Text(stringResource(R.string.embedded_chat)) }
            OutlinedButton(onClick = { Reception.identify(userId = "android-example-user", name = "Android Example") }) {
                Text(stringResource(R.string.identify))
            }
            OutlinedButton(onClick = { Reception.logout() }) { Text(stringResource(R.string.logout)) }
            Text(stringResource(R.string.unread_count, unread))
            Text(stringResource(R.string.sdk_version, Reception.version), style = MaterialTheme.typography.bodySmall)
        }
    }
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
