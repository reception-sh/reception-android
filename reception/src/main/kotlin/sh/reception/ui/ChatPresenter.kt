package sh.reception.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import sh.reception.Log
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionState
import sh.reception.client.ApplicationLifecycle
import java.lang.ref.WeakReference

/**
 * Presents the SDK's chat sheet ([ReceptionChatActivity]) over the resumed activity and dismisses it again. Main
 * thread only. A request without a resumed activity is dropped, like iOS without a window.
 */
internal object ChatPresenter {
    /** A started sheet that has not been created yet counts as presented for this long. */
    private const val START_WINDOW_MILLIS = 2_000L
    private var sheet: WeakReference<ReceptionChatActivity>? = null
    private var requestedAt: Long? = null

    /** `Reception.openChat()`. */
    fun open() {
        if (ReceptionState.session == null) return Log.error { "openChat called before configure, ignored" }
        Log.debug { "Chat presentation requested" }
        present()
    }

    /** `Reception.closeChat()`: only the SDK's own sheet; a host-embedded chat is the host's to remove. */
    fun close() {
        if (ReceptionState.session == null) return Log.error { "closeChat called before configure, ignored" }
        if (!dismiss()) Log.debug { "Chat close skipped, chat not presented by the SDK" }
    }

    val isPresented: Boolean
        get() = sheet?.get() != null ||
            requestedAt?.let { SystemClock.uptimeMillis() - it < START_WINDOW_MILLIS } == true

    fun present() {
        if (ReceptionState.isChatOpen.value || isPresented) {
            Log.debug { "Chat presentation skipped, chat already open" }
            return
        }
        val host = ApplicationLifecycle.currentActivity
        if (host == null) {
            Log.debug { "Chat presentation skipped, no resumed activity" }
            return
        }
        ReceptionAppearance.configuration.activate()
        try {
            host.startActivity(Intent(host, ReceptionChatActivity::class.java))
            requestedAt = SystemClock.uptimeMillis()
        } catch (_: ActivityNotFoundException) {
            Log.error { "Chat presentation failed, ReceptionChatActivity missing from the merged manifest" }
        }
    }

    /** A new sheet, or the same sheet recreated after a configuration change. */
    fun attached(activity: ReceptionChatActivity, recreated: Boolean) {
        sheet = WeakReference(activity)
        requestedAt = null
        if (!recreated) Log.debug { "Chat presented" }
    }

    /** The sheet closed by drag, scrim tap or Back, or its activity ended. */
    fun detached(activity: ReceptionChatActivity) {
        if (sheet?.get() !== activity) return
        sheet = null
        if (!activity.isChangingConfigurations) Log.debug { "Chat dismissed" }
    }

    /** Answers false when the SDK has not presented the chat. */
    fun dismiss(): Boolean {
        requestedAt = null
        val activity = sheet?.get() ?: return false
        sheet = null
        activity.dismiss()
        Log.debug { "Chat dismissed" }
        return true
    }

    /** Whether a touch on the sheet started on its header: the drag handle inset and the title bar. */
    fun isSheetHandle(touchStartingAtY: Float, handleHeight: Float): Boolean = touchStartingAtY <= handleHeight

    /**
     * While the keyboard is up, only a drag from the header closes the sheet; one over the messages scrolls them or
     * dismisses the keyboard. With the keyboard down, a drag anywhere closes it.
     */
    fun locksSheet(keyboardVisible: Boolean, onHeader: Boolean): Boolean = keyboardVisible && !onHeader
}
