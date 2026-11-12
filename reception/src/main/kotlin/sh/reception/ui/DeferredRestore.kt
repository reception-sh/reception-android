package sh.reception.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.Log
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.PresentationRecord
import sh.reception.client.Storage

/**
 * A sheet Android restored in a new process before the SDK was configured. Flutter and React Native configure from
 * Dart/JS, and React Native only once its activity resumed, which a sheet on top would hold back for as long as it
 * stays. So that sheet finishes at once, without drawing, and its presentation is
 * noted here. A `configure` within [DEADLINE_MILLIS] presents it again, open at once, over the app's resumed activity
 * (the next one to resume when none is), when the record still names it and the configured App ID and nothing
 * presented or dismissed the chat since. What the sheet had saved (scroll position, an open lightbox) is not carried
 * over. Main thread only.
 */
internal object DeferredRestore {
    /**
     * How long after Android recreated the sheet, the person's return to the app, `configure` and an activity may take.
     * Measured on an emulator, a release React Native app configured 0.4–0.6 s and a Flutter app 0.7–1.3 s after the
     * process start (fixes-28.md); Android vitals counts a cold start of five seconds or more as excessive, so a slow
     * device that still starts acceptably configures within it. Later, the person is likely using the app already and
     * the chat would appear over what they are doing.
     */
    private const val DEADLINE_MILLIS = 5_000L

    /** [deadline] is on [clock]: the bound counts the time the device sleeps. */
    private class Note(val presentation: String, val changes: Int, val application: Application, val deadline: Long) {
        /** The record still names this presentation and the configured App ID: it waits for a resumed activity. */
        var confirmed = false
    }

    /** Counts deep sleep too, unlike the main dispatcher's delays (uptime). */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    var clock: () -> Long = SystemClock::elapsedRealtime

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var note: Note? = null

    /** Only cleans up a note nothing came for; [pending] decides whether the bound has passed. */
    private var timer: Job? = null

    /** [sheet], restored for [presentation] before `configure`, finishes now; its presentation waits for `configure`. */
    fun noted(sheet: ReceptionChatActivity, presentation: String) {
        val note = Note(presentation, ChatPresenter.changes, sheet.application, clock() + DEADLINE_MILLIS)
        this.note = note
        timer?.cancel()
        timer = scope.launch {
            delay(DEADLINE_MILLIS)
            // A presentation or dismissal since owns the record, also when its save is still queued behind the read.
            if (this@DeferredRestore.note === note && unchanged(note)) drop(note, "Reception was not configured in time")
        }
        Log.debug { "Chat presentation waits for configure to be restored" }
    }

    /**
     * The activity a plugin is attached to received [intent] (`Reception.noteNewIntent`): the app is taken somewhere on
     * purpose (a deep link, a shortcut, a notification), so a waiting presentation is cancelled. A launch through the
     * app icon is the person's return, as for [ReturningSheet], and keeps it. A tap on the SDK's own notification opens
     * the chat through its own path afterwards.
     */
    fun newIntent(intent: Intent) {
        if (note != null && !ReturningSheet.isLauncherLaunch(intent)) cancel("the app was opened for something else")
    }

    /** The SDK was configured with [namespace]: the record is checked on the storage thread. */
    fun configured(namespace: String) {
        val note = note ?: return
        val expected = PresentationRecord.Entry(note.presentation, namespace)
        scope.launch {
            val recorded = Storage.run { PresentationRecord.load(note.application) }
            if (!pending(note)) return@launch
            if (recorded != expected) return@launch drop(note, "the chat was closed or reset since")
            note.confirmed = true
            ApplicationLifecycle.currentActivity?.let(::resumed)
        }
    }

    /** An activity of the app resumed: a confirmed presentation starts over it. */
    fun resumed(activity: Activity) {
        val note = note?.takeIf { it.confirmed } ?: return
        if (!pending(note) || activity.isFinishing || activity.isDestroyed) return
        end()
        if (ChatPresenter.restore(activity, note.presentation)) Log.debug { "Chat presentation restored after configure" }
    }

    /**
     * `closeChat()`, `logout()` or `deleteData()`, also before `configure`: the waiting presentation is not restored and
     * its record is cleared, through the note's application. Nothing else of the call happens before `configure`.
     */
    fun cancel(reason: String) {
        val note = note ?: return
        if (unchanged(note)) drop(note, reason)
    }

    /** [note] is still the one waiting, [unchanged] and within its bound; past the bound it is dropped with its record. */
    private fun pending(note: Note): Boolean {
        if (this.note !== note || !unchanged(note)) return false
        if (clock() < note.deadline) return true
        drop(note, "Reception was not configured in time")
        return false
    }

    /** Nothing presented or dismissed the chat since the sheet finished; otherwise the note ends, the record is theirs. */
    private fun unchanged(note: Note): Boolean {
        if (note.changes == ChatPresenter.changes && ChatPresenter.presentation == null) return true
        end()
        Log.debug { "Chat presentation not restored, the chat was presented or closed since" }
        return false
    }

    /** The presentation is not restored; its record, which no current presentation owns, is cleared. */
    private fun drop(note: Note, reason: String) {
        end()
        SheetRecord.clear(note.application)
        Log.debug { "Chat presentation not restored, $reason" }
    }

    private fun end() {
        note = null
        timer?.cancel()
        timer = null
    }

    /** What a new process knows: nothing. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun resetForTests() {
        end()
        clock = SystemClock::elapsedRealtime
    }
}
