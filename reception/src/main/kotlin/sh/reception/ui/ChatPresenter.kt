package sh.reception.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import sh.reception.Log
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionState
import sh.reception.client.AppTasks
import sh.reception.client.ApplicationLifecycle
import java.lang.ref.WeakReference

/**
 * Presents the SDK's chat sheet ([ReceptionChatActivity]) over the resumed activity and dismisses it again. Main
 * thread only. A request without a resumed activity is dropped, like iOS without a window.
 */
internal object ChatPresenter {
    /** A started sheet that has not been created yet counts as presented for this long. */
    private const val START_WINDOW_MILLIS = 2_000L
    /** The launch intent's extra that names the presentation a sheet belongs to. */
    const val EXTRA_PRESENTATION = "sh.reception.presentation"
    private var sheet: WeakReference<ReceptionChatActivity>? = null
    private var requestedAt: Long? = null

    /**
     * Advanced by every presentation and every dismissal. A sheet whose launch carries an older value was closed,
     * cancelled by logout or reconfiguration, or superseded before it was created, and finishes at once.
     */
    private var presentation = 0

    /** Runs once the sheet a link closed is gone, so the host can navigate or present freely. */
    private var afterDismissal: (() -> Unit)? = null

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

    /** Answers whether the sheet was started or brought to the front. */
    fun present(): Boolean {
        if (ReceptionState.isChatOpen.value || (isPresented && sheet?.get() == null)) {
            Log.debug { "Chat presentation skipped, chat already open" }
            return false
        }
        val host = ApplicationLifecycle.currentActivity
        if (host == null) {
            Log.debug { "Chat presentation skipped, no resumed activity" }
            return false
        }
        // The sheet exists but another of the app's activities covers it: bring that sheet back instead of a second one.
        sheet?.get()?.let { return bringToFront(host, it) }
        ReceptionAppearance.configuration.activate()
        presentation += 1
        return try {
            host.startActivity(Intent(host, ReceptionChatActivity::class.java).putExtra(EXTRA_PRESENTATION, presentation))
            requestedAt = SystemClock.uptimeMillis()
            true
        } catch (_: ActivityNotFoundException) {
            Log.error { "Chat presentation failed, ReceptionChatActivity missing from the merged manifest" }
            false
        }
    }

    private fun bringToFront(host: Activity, covered: ReceptionChatActivity): Boolean {
        // Reordering works inside the starting activity's task; a sheet in another task of the app moves with that task.
        if (covered.taskId != host.taskId) {
            val task = AppTasks.find(host) { it == covered.taskId } ?: return false
            task.moveToFront()
            Log.debug { "Chat brought to the front with its task" }
            return true
        }
        return try {
            host.startActivity(Intent(host, ReceptionChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            Log.debug { "Chat brought to the front" }
            true
        } catch (_: ActivityNotFoundException) {
            Log.error { "Chat presentation failed, ReceptionChatActivity missing from the merged manifest" }
            false
        }
    }

    /**
     * A new sheet, or the same sheet recreated after a configuration change, which keeps its launch intent and so its
     * presentation. Answers false for a sheet of a cancelled or superseded presentation, which must finish.
     */
    fun attached(activity: ReceptionChatActivity, presentation: Int, recreated: Boolean): Boolean {
        if (presentation != this.presentation) {
            Log.debug { "Chat presentation cancelled before it appeared" }
            return false
        }
        sheet = WeakReference(activity)
        requestedAt = null
        if (!recreated) Log.debug { "Chat presented" }
        return true
    }

    /** The sheet's activity ended; what waited for the dismissal runs now, with the host activity resumed. */
    fun destroyed(activity: ReceptionChatActivity) {
        detached(activity)
        if (activity.isChangingConfigurations) return
        val next = afterDismissal ?: return
        afterDismissal = null
        next()
    }

    /** The sheet closed by drag, scrim tap or Back, or its activity ended. */
    fun detached(activity: ReceptionChatActivity) {
        if (sheet?.get() !== activity) return
        sheet = null
        if (!activity.isChangingConfigurations) Log.debug { "Chat dismissed" }
    }

    /**
     * Also cancels a requested sheet that has not been created yet. [then] runs once the sheet is gone, or at once when
     * the SDK has no sheet on screen. Answers false when the SDK has not presented the chat.
     */
    fun dismiss(then: (() -> Unit)? = null): Boolean {
        val requested = requestedAt != null
        requestedAt = null
        presentation += 1
        val activity = sheet?.get()
        if (activity == null) {
            if (requested) Log.debug { "Chat presentation cancelled" }
            then?.invoke()
            return requested
        }
        sheet = null
        afterDismissal = then
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
