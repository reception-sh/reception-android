package sh.reception.receptionAndroidExample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.reception.ReceptionChatView

/**
 * Debug builds only: the embedded chat in a tab above a bottom navigation bar, as in Expo's tabs template or any host
 * with a bottom bar in an edge-to-edge window. The chat gets the Scaffold's padding without consuming any insets, so
 * only the part of the keyboard that covers it may lift its composer. "Taller bar" animates the bar's height, which
 * moves the chat's bottom edge without new window insets.
 */
class TabsChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ExampleDebugTools.applyWindowMode(this)
        setContent {
            MaterialTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var tall by rememberSaveable { mutableStateOf(false) }
                val extra by animateDpAsState(if (tall) 48.dp else 0.dp, label = "bar")
                Scaffold(bottomBar = {
                    Column {
                        Box(Modifier.height(extra))
                        NavigationBar {
                            val labels = listOf(R.string.tab_chat, R.string.tab_other, R.string.tab_taller_bar)
                            labels.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = if (index == 2) tall else tab == index,
                                    onClick = { if (index == 2) tall = !tall else tab = index },
                                    icon = { Text(stringResource(label)) },
                                )
                            }
                        }
                    }
                }) { padding ->
                    if (tab == 0) {
                        ReceptionChatView(Modifier.fillMaxSize().padding(padding))
                    } else {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.tab_other))
                        }
                    }
                }
            }
        }
    }
}
