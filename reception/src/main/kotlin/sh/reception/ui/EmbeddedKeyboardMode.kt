package sh.reception.ui

import android.os.Build
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

/**
 * An embedded chat lifts its composer above the keyboard with the IME insets. A host window that pans for the keyboard
 * (`adjustPan`, or `adjustUnspecified`, the default of an activity without `windowSoftInputMode`, as in Android Studio's
 * Compose template) moves it a second time, a keyboard height too far, and pushes the host's top bar off screen. Before
 * Android 11 a window that adjusts nothing gets no IME insets at all, so the composer stays behind the keyboard. While a
 * chat is embedded in such a window, the window resizes instead. A Compose `Dialog` cannot resize its content (it is
 * measured against the display, so it would overflow the shrunk window): from Android 11 it adjusts nothing and the
 * insets alone lift the composer, before it pans, which then gives no IME insets. Other dialog windows (a Material 3
 * `ModalBottomSheet` lays out across its whole window and resizes with it) follow the resize rule. Counted per window
 * (the activity's, a dialog's); when the last embedded chat in it leaves, the host's adjust mode comes back, unless the
 * host changed the mode since. Main thread only.
 */
internal object EmbeddedKeyboardMode {
    /** [installed] is the adjust mode the SDK set, null when it left the window alone. */
    private class Hold(val original: Int, val installed: Int?, var count: Int, val guard: ComposeDialogModeGuard?)

    private val holds = WeakHashMap<View, Hold>()

    fun acquire(window: KeyboardWindow) {
        holds[window.root]?.let {
            it.count += 1
            return
        }
        val original = window.softInputMode
        val adjust = original and LayoutParams.SOFT_INPUT_MASK_ADJUST
        val installed = target(window, adjust)?.takeIf { it != adjust }
        installed?.let { window.softInputMode = (original and LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or it }
        // Compose rewrites a Dialog's mode on its own recompositions below Android 12.
        val guard = installed?.takeIf { window.isComposeDialog && Build.VERSION.SDK_INT < Build.VERSION_CODES.S }
            ?.let { ComposeDialogModeGuard(window, it) }
        holds[window.root] = Hold(original, installed, 1, guard)
    }

    fun release(window: KeyboardWindow) {
        val hold = holds[window.root] ?: return
        hold.count -= 1
        if (hold.count > 0) return
        holds.remove(window.root)
        hold.guard?.stop()
        val installed = hold.installed ?: return
        val current = window.softInputMode
        // The host set its own adjust mode while the chat was shown: that one stays.
        if (current and LayoutParams.SOFT_INPUT_MASK_ADJUST != installed) return
        // Only the adjust mode goes back; state and other flags the host set meanwhile stay.
        window.softInputMode = (current and LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or restoredAdjust(window, hold.original)
    }

    // Deprecated since API 30 for apps that handle insets themselves; here it only stops the pan (or, before API 30,
    // turns on the IME insets), and every window of an edge-to-edge activity (the chat's case) gets the insets instead.
    @Suppress("DEPRECATION")
    private const val RESIZE = LayoutParams.SOFT_INPUT_ADJUST_RESIZE

    /** Compose UI's `Dialog` content view; host apps keep its name (consumer-rules.pro). */
    private const val COMPOSE_DIALOG_LAYOUT = "androidx.compose.ui.window.DialogLayout"

    /** A Compose `Dialog`'s window: its content is measured against the display and never shrinks with the window. */
    private val KeyboardWindow.isComposeDialog: Boolean
        get() = provider?.javaClass?.name == COMPOSE_DIALOG_LAYOUT

    /** The adjust mode under which the chat's IME insets lift the composer exactly once; null keeps the host's. */
    private fun target(window: KeyboardWindow, adjust: Int): Int? {
        val insetsAlways = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (window.isComposeDialog) return if (insetsAlways) LayoutParams.SOFT_INPUT_ADJUST_NOTHING else LayoutParams.SOFT_INPUT_ADJUST_PAN
        return when (adjust) {
            LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED, LayoutParams.SOFT_INPUT_ADJUST_PAN -> RESIZE
            LayoutParams.SOFT_INPUT_ADJUST_NOTHING -> if (insetsAlways) null else RESIZE
            else -> null
        }
    }

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
