package sh.reception.ui

import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import sh.reception.R

/**
 * Shows [content] in a window of its own that covers the whole display, both system bars and the display cutout
 * included, with light bar icons, while it is in the composition. Back calls [onDismiss]; leaving the composition
 * closes the window.
 *
 * Not a Compose `Dialog`: that sizes its window itself while it measures, differently in each version (1.7 the display
 * metrics centred between the bars, 1.8 and newer full width and wrapped height fitted inside the bars), so the window's
 * geometry is set here once from platform API and nothing else writes it. The content joins the chat's composition, so
 * the chat's theme and state reach it; the bar icons belong to this window and go back with it.
 */
@Composable
internal fun LightboxWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val view = LocalView.current
    val parent = rememberCompositionContext()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentContent by rememberUpdatedState(content)
    val window = remember(view) {
        FullScreenWindow(view, parent, onBack = { currentDismiss() }) { currentContent() }
    }
    DisposableEffect(window) {
        window.show()
        onDispose { window.close() }
    }
}

private class FullScreenWindow(
    host: View,
    parent: CompositionContext,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) : ComponentDialog(host.context, R.style.Reception_Lightbox) {
    private val composeView = ComposeView(context).apply {
        setParentCompositionContext(parent)
        setContent(content)
    }

    init {
        setContentView(composeView)
        onBackPressedDispatcher.addCallback(this) { onBack() }
        window?.run {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.FILL)
            addFlags(LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            if (host.isSecure()) addFlags(LayoutParams.FLAG_SECURE)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            attributes = attributes.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Before Android 11 a window without limits is told of no system bars, so the close button would
                    // sit under the status bar; there the decor's layout-fullscreen flags put the window under them.
                    flags = flags or LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    fitInsetsTypes = 0
                    fitInsetsSides = 0
                    layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.getInsetsController(this, decorView).run {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    fun close() {
        dismiss()
        composeView.disposeComposition()
    }
}

/** Like a Compose `Dialog`'s default, a host window that hides its content from screenshots hides the photo too. */
private fun View.isSecure(): Boolean =
    ((rootView.layoutParams as? LayoutParams)?.flags ?: 0) and LayoutParams.FLAG_SECURE != 0
