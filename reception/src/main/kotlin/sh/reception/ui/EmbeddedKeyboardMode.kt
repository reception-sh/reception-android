package sh.reception.ui

import android.view.Window
import android.view.WindowManager.LayoutParams
import java.util.WeakHashMap

/**
 * An embedded chat lifts its composer above the keyboard with the IME insets. A host window that pans for the keyboard
 * (`adjustPan`, or `adjustUnspecified`, the default of an activity without `windowSoftInputMode`, as in Android Studio's
 * Compose template) moves it a second time, a keyboard height too far, and pushes the host's top bar off screen. While
 * a chat is embedded in such a window, the window resizes instead; the host's mode comes back when the last embedded
 * chat in that window leaves. Main thread only.
 */
internal object EmbeddedKeyboardMode {
    private class Hold(val original: Int, val changed: Boolean, var count: Int)

    private val holds = WeakHashMap<Window, Hold>()

    fun acquire(window: Window) {
        holds[window]?.let {
            it.count += 1
            return
        }
        val original = window.attributes.softInputMode
        val adjust = original and LayoutParams.SOFT_INPUT_MASK_ADJUST
        val pans = adjust == LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED || adjust == LayoutParams.SOFT_INPUT_ADJUST_PAN
        // Deprecated since API 30 for apps that handle insets themselves; here it only stops the pan, and every
        // window of an edge-to-edge activity (the chat's case) gets the IME insets instead of a resize.
        @Suppress("DEPRECATION")
        if (pans) window.setSoftInputMode((original and LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        holds[window] = Hold(original, pans, 1)
    }

    fun release(window: Window) {
        val hold = holds[window] ?: return
        hold.count -= 1
        if (hold.count > 0) return
        holds.remove(window)
        if (hold.changed) window.setSoftInputMode(hold.original)
    }
}
