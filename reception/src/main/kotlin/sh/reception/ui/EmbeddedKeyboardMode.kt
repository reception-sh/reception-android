package sh.reception.ui

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/** Holds [EmbeddedKeyboardMode] for the window that shows this composition while it is composed. */
@Composable
internal fun EmbeddedKeyboardAdjustment() {
    val view = LocalView.current
    DisposableEffect(view) {
        // A dialog hosting the chat has its own window, apart from the activity's.
        val window = KeyboardWindow.of(view)
        window?.let(EmbeddedKeyboardMode::acquire)
        onDispose { window?.let(EmbeddedKeyboardMode::release) }
    }
}

/**
 * An embedded chat lifts its composer above the keyboard with the IME insets. A host window that pans for the keyboard
 * (`adjustPan`, or `adjustUnspecified`, the default of an activity without `windowSoftInputMode`, as in Android Studio's
 * Compose template) moves it a second time, a keyboard height too far, and pushes the host's top bar off screen. While a
 * chat is embedded in such a window, the window resizes instead, counted per window (the activity's, a dialog's); when
 * the last embedded chat in it leaves, the host's mode comes back. Main thread only.
 */
internal object EmbeddedKeyboardMode {
    /** [installed] is the adjust mode the SDK set, null when it left the window alone. */
    private class Hold(val original: Int, val installed: Int?, var count: Int)

    private val holds = WeakHashMap<View, Hold>()

    fun acquire(window: KeyboardWindow) {
        holds[window.root]?.let {
            it.count += 1
            return
        }
        val original = window.softInputMode
        val installed = if (adjusts(original and LayoutParams.SOFT_INPUT_MASK_ADJUST)) RESIZE else null
        installed?.let { window.softInputMode = (original and LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or it }
        holds[window.root] = Hold(original, installed, 1)
    }

    fun release(window: KeyboardWindow) {
        val hold = holds[window.root] ?: return
        hold.count -= 1
        if (hold.count > 0) return
        holds.remove(window.root)
        if (hold.installed == null) return
        window.softInputMode = (hold.original and LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or restoredAdjust(window, hold.original)
    }

    // Deprecated since API 30 for apps that handle insets themselves; here it only stops the pan, and every window of an
    // edge-to-edge activity (the chat's case) gets the IME insets instead of a resize.
    @Suppress("DEPRECATION")
    private const val RESIZE = LayoutParams.SOFT_INPUT_ADJUST_RESIZE

    private fun adjusts(adjust: Int): Boolean =
        adjust == LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED || adjust == LayoutParams.SOFT_INPUT_ADJUST_PAN

    /**
     * Android keeps the last adjust mode when a window asks for "unspecified" again, so an unspecified original comes
     * back as what Android would have chosen for it: resize when a shown view is a scroll container, else pan.
     */
    private fun restoredAdjust(window: KeyboardWindow, original: Int): Int {
        val adjust = original and LayoutParams.SOFT_INPUT_MASK_ADJUST
        if (adjust != LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) return adjust
        return if (window.root.hasShownScrollContainer()) RESIZE else LayoutParams.SOFT_INPUT_ADJUST_PAN
    }

    private fun View.hasShownScrollContainer(): Boolean {
        if (isScrollContainer && isShown) return true
        val group = this as? ViewGroup ?: return false
        return (0 until group.childCount).any { group.getChildAt(it).hasShownScrollContainer() }
    }
}
