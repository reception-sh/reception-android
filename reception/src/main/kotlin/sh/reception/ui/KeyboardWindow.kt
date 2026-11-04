package sh.reception.ui

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.window.DialogWindowProvider

/**
 * The window that shows a view, for its soft-input mode: a Compose `Dialog`'s, the activity's, or another window the
 * view's root was added with (a platform dialog's), reached through the root's window layout parameters, which are the
 * same object as that window's attributes. [root] identifies the window. Main thread only.
 */
internal class KeyboardWindow private constructor(
    val root: View,
    private val window: Window?,
    /** A Compose `Dialog`'s window, whose content is measured against the display and never shrinks with the window. */
    val isComposeDialog: Boolean = false,
) {
    var softInputMode: Int
        get() = window?.attributes?.softInputMode ?: parameters?.softInputMode ?: 0
        set(value) {
            if (window != null) return window.setSoftInputMode(value)
            val parameters = parameters ?: return
            parameters.softInputMode = value
            // A removed window keeps the value for the next time it is shown.
            if (root.isAttachedToWindow) root.context.getSystemService(WindowManager::class.java)?.updateViewLayout(root, parameters)
        }

    private val parameters: WindowManager.LayoutParams?
        get() = root.layoutParams as? WindowManager.LayoutParams

    companion object {
        /** The window [view] is attached to; null before it is attached. */
        fun of(view: View): KeyboardWindow? {
            var current: Any? = view
            while (current is View) {
                if (current is DialogWindowProvider) return KeyboardWindow(current.window.decorView, current.window, isComposeDialog = true)
                current = current.parent
            }
            val root = view.rootView.takeIf { view.isAttachedToWindow } ?: return null
            val activityWindow = view.context.findActivity()?.window?.takeIf { it.decorView === root }
            return KeyboardWindow(root, activityWindow)
        }
    }
}
