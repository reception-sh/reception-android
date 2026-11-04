package sh.reception.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import sh.reception.Log
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionState
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.PresentationRecord
import sh.reception.client.Storage
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Presents the SDK's chat sheet ([ReceptionChatActivity]) over the resumed activity and dismisses it again. Main
 * thread only. A request without a resumed activity is dropped, like iOS without a window.
 */
internal object ChatPresenter {
    /** A started sheet that has not been created yet counts as presented for this long. */
    private const val START_WINDOW_MILLIS = 2_000L
    /** How long a sheet restored after process death waits for a host that configures late. */
    private const val RESTORE_WAIT_MILLIS = 3_000L
    /** The launch intent's extra that names the presentation a sheet belongs to. */
    const val EXTRA_PRESENTATION = "sh.reception.presentation"
    private var sheet: WeakReference<ReceptionChatActivity>? = null
    private var requestedAt: Long? = null

    /**
     * The current presentation's random token, which its sheet's launch intent carries; null after a dismissal. A sheet
     * with another token was closed, cancelled by logout, deletion or reconfiguration, or superseded, and finishes at
     * once. The token is also recorded on disk ([PresentationRecord]), so the sheet Android restores after the process
     * died is shown again.
     */
    private var presentation: String? = null

    /** Advanced by every presentation and dismissal, so a restore that read the record before one is rejected. */
    private var changes = 0

    /** Runs once the sheet a link closed is gone, so the host can navigate or present freely. */
    private var afterDismissal: (() -> Unit)? = null

    /**
     * `Reception.openChat()`, and the opens a push or a tap asks for: [stillWanted] is asked again right before the
     * sheet starts, so a request withdrawn while its record was being saved (routing turned off) does not appear.
     */
    fun open(stillWanted: () -> Boolean = { true }) {
        if (ReceptionState.session == null) return Log.error { "openChat called before configure, ignored" }
        Log.debug { "Chat presentation requested" }
        present(stillWanted)
    }

    /** `Reception.closeChat()`: only the SDK's own sheet; a host-embedded chat is the host's to remove. */
    fun close() {
        if (ReceptionState.session == null) return Log.error { "closeChat called before configure, ignored" }
        if (!dismiss()) Log.debug { "Chat close skipped, chat not presented by the SDK" }
    }

    val isPresented: Boolean
        get() = sheet?.get() != null ||
            requestedAt?.let { SystemClock.uptimeMillis() - it < START_WINDOW_MILLIS } == true

    /**
     * Answers whether the sheet was requested (it starts once its record is on disk) or brought to the front.
     * [stillWanted] is asked right before the sheet starts; false cancels the presentation.
     */
    fun present(stillWanted: () -> Boolean = { true }): Boolean {
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
        sheet?.get()?.let { return CoveredSheet.bringToFront(host, it) }
        ReceptionAppearance.configuration.activate()
        val token = UUID.randomUUID().toString()
        presentation = token
        changes += 1
        requestedAt = SystemClock.uptimeMillis()
        val entry = ReceptionState.namespace?.let { PresentationRecord.Entry(token, it) }
            ?: return startIfWanted(host, token, stillWanted)
        SheetRecord.save(host, entry) { startIfWanted(host, token, stillWanted) }
        return true
    }

    /** The save took a moment: the presentation may have been closed or superseded, or its screen left. */
    private fun startIfWanted(host: Activity, token: String, stillWanted: () -> Boolean): Boolean {
        if (presentation != token) return false
        val reason = when {
            ApplicationLifecycle.currentActivity !== host -> "the screen changed before it started"
            !stillWanted() -> "it was withdrawn before it started"
            else -> return start(host, token)
        }
        Log.debug { "Chat presentation cancelled, $reason" }
        dismiss()
        return false
    }

    private fun start(host: Activity, token: String): Boolean = try {
        host.startActivity(Intent(host, ReceptionChatActivity::class.java).putExtra(EXTRA_PRESENTATION, token))
        requestedAt = SystemClock.uptimeMillis()
        true
    } catch (_: ActivityNotFoundException) {
        Log.error { "Chat presentation failed, ReceptionChatActivity missing from the merged manifest" }
        requestedAt = null
        false
    }

    /** An activity of the app resumed: it may be the one to reorder a covered sheet ([CoveredSheet]). */
    fun activityResumed(activity: Activity) = CoveredSheet.activityResumed(activity, sheet?.get())

    /**
     * A new sheet, or the same sheet recreated after a configuration change, which keeps its launch intent and so its
     * presentation. Answers false for a sheet of a cancelled or superseded presentation, which must finish.
     */
    fun attached(activity: ReceptionChatActivity, presentation: String?, recreated: Boolean): Boolean {
        if (presentation == null || presentation != this.presentation) {
            // A recreated sheet may still be restored; ReceptionChatActivity asks [restored] next.
            if (!recreated) Log.debug { "Chat presentation cancelled before it appeared" }
            return false
        }
        accept(activity)
        if (!recreated) Log.debug { "Chat presented" }
        return true
    }

    /**
     * A sheet Android restored in a new process (the old one died with the sheet on screen). It is shown again when it
     * belongs to the recorded presentation of the configured App ID and nothing presented or dismissed the chat since.
     * Flutter and React Native configure from Dart/JS after Android recreated the sheet, so the sheet waits a moment.
     */
    suspend fun restored(activity: ReceptionChatActivity, presentation: String): Boolean {
        val before = changes
        val namespace = ReceptionState.namespace ?: ReceptionState.configuredNamespace(RESTORE_WAIT_MILLIS)
        if (namespace == null) {
            Log.debug { "Chat presentation not restored, Reception was not configured in time" }
            return false
        }
        val expected = PresentationRecord.Entry(presentation, namespace)
        val recorded = Storage.run { PresentationRecord.load(activity) }
        if (before != changes || this.presentation != null || recorded != expected) {
            Log.debug { "Chat presentation not restored, the chat was closed or reset since" }
            return false
        }
        this.presentation = presentation
        accept(activity)
        Log.debug { "Chat presentation restored" }
        return true
    }

    private fun accept(activity: ReceptionChatActivity) {
        sheet = WeakReference(activity)
        requestedAt = null
    }

    /** The sheet's activity ended; what waited for the dismissal runs now, with the host activity resumed. */
    fun destroyed(activity: ReceptionChatActivity) {
        detached(activity)
        if (activity.isChangingConfigurations) return
        // The sheet is gone for good (not destroyed to be restored later): nothing of it is left to restore.
        if (activity.isFinishing && activity.presentation != null && activity.presentation == presentation) {
            presentation = null
            changes += 1
            SheetRecord.clear(activity)
        }
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
        presentation = null
        changes += 1
        CoveredSheet.cancel()
        ReceptionState.session?.store?.context?.let { SheetRecord.clear(it) }
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

    /** What a new process knows: nothing in memory; the record on disk stays. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun resetForTests() {
        sheet = null
        requestedAt = null
        presentation = null
        afterDismissal = null
    }
}
