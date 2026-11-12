package sh.reception.ui

import android.os.Bundle
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import sh.reception.ReceptionState

/**
 * The SDK's chat sheet: a translucent activity over the host's activity, so it works over any host (Flutter and React
 * Native included) and survives configuration changes. It draws only the bottom sheet; [ChatPresenter] starts it.
 */
internal class ReceptionChatActivity : ComponentActivity() {
    private var dismissRequested by mutableStateOf(false)
    /**
     * The chat reported itself open (`ChatOpened`, `isChatOpen`): it composed, or this is a recreated sheet whose
     * predecessor did and handed its visibility over.
     */
    private var shown = false

    /**
     * The presenter accepted this sheet for its presentation: only such a sheet's end ends it. A sheet that finished
     * without (cancelled, or restored before configure) is destroyed later, when its token may be current again.
     */
    private var accepted = false

    /** The SDK finished this sheet (a close, a cancelled presentation); otherwise the system did ([ReturningSheet]). */
    var finishedBySdk: Boolean = false
        private set

    /**
     * Stopped while open and not since started: covered or left, not closing (a finish of a visible sheet marks it
     * finishing before its stop). Only such a sheet can come back after a launcher return ([ReturningSheet]).
     */
    var stoppedOpen: Boolean = false
        private set

    /** The presentation this sheet was started for. */
    val presentation: String?
        get() = intent.getStringExtra(SheetLaunch.EXTRA_PRESENTATION)

    /** A sheet that comes back after a launcher return finished its predecessor shows open at once. */
    private val returning: Boolean
        get() = intent.getBooleanExtra(SheetLaunch.EXTRA_RETURNING, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val presentation = presentation
        val recreated = savedInstanceState != null
        if (recreated) keepKeyboardState()
        shown = recreated
        if (ChatPresenter.attached(this, presentation, recreated)) return show()
        if (!recreated || presentation == null) return finishBySdk()
        // Restored after the process died, before configure: this sheet would hold back a host that configures once its
        // own activity resumed, so it ends now and the SDK presents the chat again on configure.
        val namespace = ReceptionState.namespace
        if (namespace == null) {
            DeferredRestore.noted(this, presentation)
            return finishBySdk()
        }
        // Restored after the process died: the presentation is checked against the record on the storage thread, and
        // the content (with its saved state, the lightbox's included) is set once the answer is in.
        lifecycleScope.launch {
            if (SheetReturn.restored(this@ReceptionChatActivity, presentation, namespace)) show() else finishBySdk()
        }
    }

    /**
     * The manifest's `stateHidden` opens a new sheet without the keyboard. Android treats a relaunch after a
     * configuration change and a bring-to-front over the activity that covered the sheet as forward navigation, where
     * that state would close a keyboard the person has open; from then on this sheet leaves the keyboard as it is.
     */
    fun keepKeyboardState() {
        val otherBits = window.attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_STATE.inv()
        window.setSoftInputMode(otherBits or LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
    }

    private fun show() {
        accepted = true
        setContent {
            // Closed before its first frame: nothing was on screen, so the chat never reports itself open.
            if (dismissRequested && !shown) return@setContent
            shown = true
            ChatSheet(dismissRequested = dismissRequested, opensOpen = returning, onDismissed = ::finishSheet)
        }
    }

    /**
     * Slides the sheet away, then finishes. A sheet that has not drawn yet (closed right after it started, as a
     * `closeChat()` just after an automation opened it) finishes at once, so no `ChatOpened` follows its dismissal.
     */
    fun dismiss() {
        dismissRequested = true
        if (!shown) finishSheet()
    }

    private fun finishSheet() {
        if (isFinishing) return
        ChatPresenter.detached(this)
        finishBySdk()
    }

    private fun finishBySdk() {
        finishedBySdk = true
        finish()
    }

    override fun onStart() {
        super.onStart()
        stoppedOpen = false
    }

    override fun onStop() {
        super.onStop()
        stoppedOpen = !isChangingConfigurations && !isFinishing
    }

    override fun onDestroy() {
        if (accepted) ChatPresenter.destroyed(this)
        super.onDestroy()
    }
}
