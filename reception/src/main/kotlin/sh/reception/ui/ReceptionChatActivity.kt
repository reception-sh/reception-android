package sh.reception.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The SDK's chat sheet: a translucent activity over the host's activity, so it works over any host (Flutter and React
 * Native included) and survives configuration changes. It draws only the bottom sheet; [ChatPresenter] starts it.
 */
internal class ReceptionChatActivity : ComponentActivity() {
    private var dismissRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ChatPresenter.attached(this, recreated = savedInstanceState != null)
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
        ChatPresenter.detached(this)
        super.onDestroy()
    }
}
