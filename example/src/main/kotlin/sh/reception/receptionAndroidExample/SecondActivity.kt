package sh.reception.receptionAndroidExample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.reception.Reception

/**
 * A screen above the launcher activity, where a notification tap must open the chat as well. Its note field opens the
 * keyboard here, so a covered chat brought back with "Support" shows whether the keyboard stays open.
 */
class SecondActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.second_screen), style = MaterialTheme.typography.titleLarge)
                        var note by rememberSaveable { mutableStateOf("") }
                        OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.note)) })
                        Button(onClick = { Reception.openChat() }) { Text(stringResource(R.string.support)) }
                        OutlinedButton(onClick = ::finish) { Text(stringResource(R.string.back)) }
                    }
                }
            }
        }
    }
}
