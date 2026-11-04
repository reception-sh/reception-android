package sh.reception.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The SDK's chat sheet: a translucent activity over the host's activity, so it works over any host (Flutter and React
 * Native included) and survives configuration changes. It draws only the bottom sheet; [ChatPresenter] starts it.
 */
internal class ReceptionChatActivity : ComponentActivity() {
    private var dismissRequested by mutableStateOf(false)

    /** The presentation this sheet was started for. */
    val presentation: String?
        get() = intent.getStringExtra(ChatPresenter.EXTRA_PRESENTATION)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val presentation = presentation
        val recreated = savedInstanceState != null
        if (ChatPresenter.attached(this, presentation, recreated)) return show()
        // Restored after the process died: the presentation is checked against the record on the storage thread, and
        // the content (with its saved state, the lightbox's included) is set once the answer is in.
        if (!recreated || presentation == null) return finish()
        lifecycleScope.launch {
            if (ChatPresenter.restored(this@ReceptionChatActivity, presentation)) show() else finish()
        }
    }

    private fun show() {
        setContent {
            ChatSheet(dismissRequested = dismissRequested, onDismissed = ::finishSheet)
        }
    }

    /** Slides the sheet away, then finishes. */
    fun dismiss() {
        dismissRequested = true
    }

    private fun finishSheet() {
        if (isFinishing) return
        ChatPresenter.detached(this)
        finish()
    }

    override fun onDestroy() {
        ChatPresenter.destroyed(this)
        super.onDestroy()
    }
}
