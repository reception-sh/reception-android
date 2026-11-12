package sh.reception.receptionAndroidExample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.reception.Reception

/**
 * Debug builds only: an activity in a second process (`android:process=":other"`). The example's `Application.onCreate`
 * runs here too and its configure is refused with one error line; "Open chat here" then behaves as before configure,
 * and "Push message here" hands a Reception payload to [OtherProcessService], whose `handlePushMessage` claims it and
 * shows nothing. The main process is unaffected.
 */
class OtherProcessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.other_process_title))
                        OutlinedButton(onClick = Reception::openChat) { Text(stringResource(R.string.open_chat_here)) }
                        OutlinedButton(onClick = {
                            startService(Intent(this@OtherProcessActivity, OtherProcessService::class.java))
                        }) { Text(stringResource(R.string.push_message_here)) }
                    }
                }
            }
        }
    }
}
