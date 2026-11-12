package sh.reception.receptionAndroidExample

import android.os.Bundle
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import sh.reception.ReceptionChatView

/**
 * Debug builds only: a `ReceptionChatView` whose composition outlives detaching, moved from the activity's window into a
 * dialog's and back without recomposing, as a host that keeps its views does. In either window, its composer must sit
 * directly on the keyboard.
 */
class MovingChatActivity : ComponentActivity() {
    private lateinit var chat: ReceptionChatView
    private lateinit var container: FrameLayout
    private var dialog: ComponentDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        chat = ReceptionChatView(this).apply {
            // The activity's owners on the view itself: in the dialog it finds the same owners, so nothing recomposes.
            setViewTreeLifecycleOwner(this@MovingChatActivity)
            setViewTreeSavedStateRegistryOwner(this@MovingChatActivity)
            setViewTreeViewModelStoreOwner(this@MovingChatActivity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@MovingChatActivity))
        }
        container = FrameLayout(this).apply { addView(chat) }
        val move = Button(this).apply {
            setText(R.string.move_chat_into_dialog)
            setOnClickListener { moveIntoDialog() }
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(move)
            addView(container, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        // Only the button keeps clear of the status bar; the chat pads for the keyboard and navigation bar itself.
        ViewCompat.setOnApplyWindowInsetsListener(move) { view, insets ->
            (view.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            insets
        }
        setContentView(screen)
    }

    /** Into a dialog 60 % of the screen tall; dismissing it brings the chat back. */
    private fun moveIntoDialog() {
        container.removeView(chat)
        dialog = ComponentDialog(this).apply {
            setContentView(chat)
            window?.setLayout(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6f).toInt())
            setOnDismissListener {
                (chat.parent as? ViewGroup)?.removeView(chat)
                container.addView(chat)
                dialog = null
            }
            show()
        }
    }

    override fun onDestroy() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        super.onDestroy()
    }
}
