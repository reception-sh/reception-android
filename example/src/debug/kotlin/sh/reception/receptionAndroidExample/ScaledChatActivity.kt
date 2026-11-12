package sh.reception.receptionAndroidExample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import sh.reception.ReceptionChatView

/**
 * Debug builds only: an embedded chat the host scales, as a transition or a scaled container does. "Smaller" animates
 * it to 80 % around its bottom edge, which stays on the window's bottom edge; with the keyboard open, its composer must
 * stay directly on the keyboard through the animation and after it.
 */
class ScaledChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var small by rememberSaveable { mutableStateOf(false) }
                    val scale by animateFloatAsState(if (small) 0.8f else 1f, label = "scale")
                    Column(Modifier.fillMaxSize()) {
                        OutlinedButton(onClick = { small = !small }, modifier = Modifier.statusBarsPadding()) {
                            Text(stringResource(R.string.smaller))
                        }
                        ReceptionChatView(Modifier.fillMaxWidth().weight(1f).graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        })
                    }
                }
            }
        }
    }
}
