package sh.reception.ui

import android.view.ViewTreeObserver
import android.view.WindowManager.LayoutParams

/**
 * Keeps the SDK's adjust mode on a Compose `Dialog`'s window below Android 12. Compose UI 1.7.6's
 * `DialogWrapper.updateParameters`, which runs after every recomposition of the `Dialog` call, writes the window's
 * whole soft-input mode there: `adjustResize` without `decorFitsSystemWindows`, else the adjust mode the dialog's theme
 * gave the window. Before each frame, while a chat is embedded, a mode that is exactly one of those two values goes back
 * to [installed], the chat's adjust mode, also when the window already had it and the SDK wrote nothing (the state bits
 * Compose cleared stay cleared). Any other mode is the host's own choice and stays. Main thread only.
 */
internal class ComposeDialogModeGuard(private val window: KeyboardWindow, private val installed: Int) :
    ViewTreeObserver.OnPreDrawListener {
    private val written = setOf(RESIZE, themeAdjust())
    private var stopped = false

    init {
        window.root.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onPreDraw(): Boolean {
        val current = window.softInputMode
        // A listener left on an observer of a window that went away stays inert.
        if (!stopped && current and LayoutParams.SOFT_INPUT_MASK_ADJUST != installed && current in written) {
            window.softInputMode = (current and LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or installed
        }
        return true
    }

    fun stop() {
        stopped = true
        window.root.viewTreeObserver.removeOnPreDrawListener(this)
    }

    private fun themeAdjust(): Int {
        val attributes = window.root.context.obtainStyledAttributes(intArrayOf(android.R.attr.windowSoftInputMode))
        return try {
            attributes.getInt(0, 0) and LayoutParams.SOFT_INPUT_MASK_ADJUST
        } finally {
            attributes.recycle()
        }
    }

    private companion object {
        @Suppress("DEPRECATION")
        const val RESIZE = LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
}
