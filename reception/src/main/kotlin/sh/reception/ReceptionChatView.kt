package sh.reception

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalView
import sh.reception.ui.ChatContent
import sh.reception.ui.ChatRoot
import sh.reception.ui.EmbeddedKeyboardMode
import sh.reception.ui.findActivity

/**
 * The chat screen without the sheet, for the app's own navigation or presentation. Use [Reception.openChat] for the
 * standard sheet instead; an action that shows this view must not also call it. It follows the enclosing
 * `MaterialTheme` (dark mode and accent) unless that is Material's unthemed baseline.
 */
@Composable
public fun ReceptionChatView(modifier: Modifier = Modifier) {
    val window = LocalView.current.context.findActivity()?.window
    DisposableEffect(window) {
        window?.let(EmbeddedKeyboardMode::acquire)
        onDispose { window?.let(EmbeddedKeyboardMode::release) }
    }
    Box(modifier) {
        ChatRoot(embedded = true) { ChatContent(showsSheetControls = false) }
    }
}

/**
 * [ReceptionChatView] as an Android View, for XML layouts and Flutter or React Native platform views. The host must
 * provide a `ViewTreeLifecycleOwner`, as every activity does.
 */
public class ReceptionChatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
    @Composable
    override fun Content() {
        ReceptionChatView(Modifier.fillMaxSize())
    }
}
