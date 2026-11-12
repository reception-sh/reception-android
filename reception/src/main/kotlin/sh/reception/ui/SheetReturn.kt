package sh.reception.ui

import android.app.Activity
import sh.reception.Log
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.PresentationRecord
import sh.reception.client.Storage

/**
 * A presented sheet that comes again after its activity went without a close: restored by Android in a new process,
 * or brought back after a return through the launcher icon finished it ([ReturningSheet]). Coordinates both with the
 * presentation ([ChatPresenter]). Main thread only.
 */
internal object SheetReturn {
    /**
     * A sheet Android restored in a new process (the old one died with the sheet on screen) after the SDK was configured
     * with [namespace]. It is shown again when it belongs to the recorded presentation of that App ID and nothing
     * presented or dismissed the chat since. A sheet restored before `configure` goes to [DeferredRestore] instead.
     */
    suspend fun restored(activity: ReceptionChatActivity, presentation: String, namespace: String): Boolean {
        val before = ChatPresenter.changes
        val expected = PresentationRecord.Entry(presentation, namespace)
        val recorded = Storage.run { PresentationRecord.load(activity) }
        if (before != ChatPresenter.changes || ChatPresenter.presentation != null || recorded != expected) {
            Log.debug { "Chat presentation not restored, the chat was closed or reset since" }
            return false
        }
        ChatPresenter.adoptRestored(activity, presentation)
        Log.debug { "Chat presentation restored" }
        return true
    }

    /**
     * [sheet], the current presentation's, ended: answers whether the system finished it while it was stopped and open
     * (not the SDK, not the person). Its presentation then waits for the app's next resumed activity, which decides
     * ([ReturningSheet]); an activity resumed before the sheet ended decides now.
     */
    fun finishedBySystem(sheet: ReceptionChatActivity): Boolean {
        val token = sheet.presentation
        if (!sheet.isFinishing || sheet.finishedBySdk || !sheet.stoppedOpen || token == null) return false
        Log.debug { "Chat closed by the system while it was not shown, the next screen decides whether it comes back" }
        ReturningSheet.finishedBySystem(token, sheet.taskId, ApplicationLifecycle.currentActivity)?.let(::decided)
        return true
    }

    /** An activity resumed: a sheet that a launcher return finished starts again over it, any other ends. */
    fun resumed(activity: Activity) {
        ReturningSheet.resumed(activity)?.let(::decided)
    }

    private fun decided(decision: ReturningSheet.Decision) {
        if (decision.token != ChatPresenter.presentation) return
        val host = decision.host
        when {
            host == null -> ChatPresenter.dismiss(reason = "its sheet was closed by the system")
            ChatPresenter.start(host, decision.token, returning = true) -> Log.debug { "Chat brought back" }
            else -> ChatPresenter.dismiss()
        }
    }
}
