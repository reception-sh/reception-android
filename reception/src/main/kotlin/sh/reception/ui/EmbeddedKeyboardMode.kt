package sh.reception.ui

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams
import androidx.annotation.ChecksSdkIntAtLeast
import sh.reception.Log
import java.util.Collections
import java.util.WeakHashMap

/**
 * An embedded chat lifts its composer above the keyboard with the IME insets. A host window that pans for the keyboard
 * (`adjustPan`, or `adjustUnspecified`, the default of an activity without `windowSoftInputMode`, as in Android Studio's
 * Compose template) moves it a second time, a keyboard height too far, and pushes the host's top bar off screen. Before
 * Android 11 a window that adjusts nothing gets no IME insets at all, so the composer stays behind the keyboard. While a
 * chat is embedded in such a window, the window resizes instead. A Compose `Dialog` adjusts nothing from Android 11, so
 * an edge-to-edge one lifts its composer with the insets, frame by frame, and is never resized (it measures its content
 * against the display, a resize would clip it); one that turns out to swallow the keyboard's inset above the chat
 * ([SwallowedKeyboardInset], a decor that fits system windows) resizes from then on, and its content follows the
 * window. The dialog's flags are never read: they do not tell reliably whether the decor fits. Before Android 11 a
 * Compose `Dialog` pans, as only a resize would send insets there. Other dialog windows (a host's Material 3
 * `ModalBottomSheet` lays out across its whole window and resizes with it) follow the resize rule. Counted per window
 * (the activity's, a dialog's); when the last embedded chat in it leaves, the host's adjust mode comes back. The SDK
 * changes a window's adjust mode only while the window still carries the mode the SDK expects there: once the host set
 * its own, that window keeps it until its chats leave, nothing is written or restored over it. Main thread only.
 */
internal object EmbeddedKeyboardMode {
    /**
     * [expected] is the adjust mode the window carries as the SDK left it: the one it last wrote, or the host's when it
     * needed no write. [wrote] says whether the SDK changed it, so only then the host's [original] comes back.
     */
    private class Hold(val original: Int, var expected: Int, var wrote: Boolean, var guard: ComposeDialogModeGuard?) {
        var count = 1
        val shows = KeyboardShows()

        /** The host set its own adjust mode on the window: the SDK leaves the window's mode alone until the hold ends. */
        var abandoned = false
    }

    private val holds = WeakHashMap<View, Hold>()

    /** The windows that turned out to swallow the keyboard's inset above the chat, for as long as each exists. */
    private val swallowing: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    fun acquire(window: KeyboardWindow) {
        holds[window.root]?.let {
            it.count += 1
            return
        }
        val original = window.softInputMode
        val adjust = original and LayoutParams.SOFT_INPUT_MASK_ADJUST
        val desired = target(window, adjust)
        val installed = desired?.takeIf { it != adjust }
        installed?.let { window.softInputMode = original.withAdjust(it) }
        val guard = desired?.let { guard(window, it) }
        holds[window.root] = Hold(original, expected = desired ?: adjust, wrote = installed != null, guard)
    }

    fun release(window: KeyboardWindow) {
        val hold = holds[window.root] ?: return
        hold.count -= 1
        if (hold.count > 0) return
        holds.remove(window.root)
        // Only the adjust mode goes back; state and other flags the host set meanwhile stay.
        if (hold.wrote && owns(window, hold)) {
            window.softInputMode = window.softInputMode.withAdjust(restoredAdjust(window, hold.original))
        }
        hold.guard?.stop()
    }

    /** Whether [window] holds a chat whose keyboard inset it may swallow: a Compose `Dialog`'s, from Android 11. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    private fun watchesKeyboardInset(window: KeyboardWindow): Boolean =
        window.root in holds && window.isComposeDialog && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The keyboard shows of [window] while it holds a chat whose keyboard inset it may swallow; null for other windows. */
    fun keyboardShows(window: KeyboardWindow): KeyboardShows? =
        holds[window.root]?.shows?.takeIf { watchesKeyboardInset(window) }

    /**
     * [window] swallowed the keyboard's inset above the chat. One that adjusts nothing resizes from now on, also for
     * chats that join it later, and gets the host's mode back like any other window when the last chat leaves. One that
     * already resizes missed the keyboard (Android 16, app targeting 34: about every second show leaves the window at
     * full height) and lays out again, as writing its mode makes it do. A window whose host set its own mode is left
     * alone.
     */
    fun keyboardInsetSwallowed(window: KeyboardWindow) {
        if (!watchesKeyboardInset(window)) return
        val hold = holds[window.root] ?: return
        if (!owns(window, hold)) return
        window.softInputMode = window.softInputMode.withAdjust(RESIZE)
        if (!swallowing.add(window.root)) return
        hold.expected = RESIZE
        hold.wrote = true
        hold.guard?.stop()
        hold.guard = guard(window, RESIZE)
    }

    /**
     * Whether [window] still carries the adjust mode the SDK expects; below Android 12 a Compose `Dialog` may carry
     * Compose's own rewrite, which [ComposeDialogModeGuard] repairs. Any other mode is the host's: the SDK gives up
     * adjusting the window, says so once, and never writes or restores over it.
     */
    private fun owns(window: KeyboardWindow, hold: Hold): Boolean {
        if (hold.abandoned) return false
        val mode = window.softInputMode
        if (mode and LayoutParams.SOFT_INPUT_MASK_ADJUST == hold.expected || hold.guard?.repairs(mode) == true) return true
        hold.abandoned = true
        hold.guard?.stop()
        hold.guard = null
        Log.debug { "The app set its own keyboard mode on the chat's window, the chat leaves that window's mode alone" }
        return false
    }

    private fun Int.withAdjust(adjust: Int): Int = (this and LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or adjust

    /** Compose rewrites a Dialog's mode on its own recompositions below Android 12, also when nothing was written. */
    private fun guard(window: KeyboardWindow, adjust: Int): ComposeDialogModeGuard? {
        if (!window.isComposeDialog || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
        return ComposeDialogModeGuard(window, adjust) { holds[window.root]?.let { owns(window, it) } == true }
    }

    // Deprecated since API 30 for apps that handle insets themselves; here it only stops the pan (or, before API 30,
    // turns on the IME insets), and every window of an edge-to-edge activity (the chat's case) gets the insets instead.
    @Suppress("DEPRECATION")
    private const val RESIZE = LayoutParams.SOFT_INPUT_ADJUST_RESIZE

    /** Compose UI's `Dialog` content view; host apps keep its name (consumer-rules.pro). */
    private const val COMPOSE_DIALOG_LAYOUT = "androidx.compose.ui.window.DialogLayout"

    /** A Compose `Dialog`'s window. */
    private val KeyboardWindow.isComposeDialog: Boolean
        get() = provider?.javaClass?.name == COMPOSE_DIALOG_LAYOUT

    /** The adjust mode under which the chat's IME insets lift the composer exactly once; null keeps the host's. */
    private fun target(window: KeyboardWindow, adjust: Int): Int? {
        val insetsAlways = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (window.isComposeDialog) {
            return when {
                !insetsAlways -> LayoutParams.SOFT_INPUT_ADJUST_PAN
                window.root in swallowing -> RESIZE
                else -> LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            }
        }
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
