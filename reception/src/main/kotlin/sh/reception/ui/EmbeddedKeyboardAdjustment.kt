package sh.reception.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** Holds [EmbeddedKeyboardMode] for the window that shows this composition while it is composed. */
@Composable
internal fun EmbeddedKeyboardAdjustment() {
    val view = LocalView.current
    DisposableEffect(view) {
        val adjustment = WindowAdjustment(view)
        onDispose(adjustment::dispose)
    }
}

/**
 * Follows [view] between windows: a retained composition (a `ComposeView` that outlives detaching) can be moved from
 * the activity into a dialog. A dialog hosting the chat has its own window, apart from the activity's.
 */
private class WindowAdjustment(private val view: View) : View.OnAttachStateChangeListener {
    private var window: KeyboardWindow? = null
    private var disposed = false

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    override fun onViewAttachedToWindow(attached: View) {
        if (disposed || window != null) return
        window = KeyboardWindow.of(view)?.also(EmbeddedKeyboardMode::acquire)
    }

    override fun onViewDetachedFromWindow(detached: View) {
        window?.let(EmbeddedKeyboardMode::release)
        window = null
    }

    /** Idempotent: the composition can end after the view already left its window. */
    fun dispose() {
        disposed = true
        view.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(view)
    }
}
