package sh.reception.receptionAndroidExample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Debug builds only: a host screen in [SingleTaskActivity]'s task that covers the chat's sheet, and navigates back to
 * the root with `FLAG_ACTIVITY_CLEAR_TOP`, as a host's own navigation does (the audit's A → sheet → B → CLEAR_TOP case).
 */
class SingleTaskCoverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.single_task_cover_title))
                        OutlinedButton(onClick = {
                            startActivity(
                                Intent(this@SingleTaskCoverActivity, SingleTaskActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                        }) { Text(stringResource(R.string.back_to_start_clear_top)) }
                    }
                }
            }
        }
    }
}
