package sh.reception

import android.app.Activity
import sh.reception.ui.ChatPresenter

/**
 * A chat that opens over the next activity that resumes: after a tap on one of the SDK's notifications, or the host's
 * own `handlePushNotification(openChat = true)` while no activity is resumed. Turning the SDK's routing off cancels
 * only the taps' requests, also one already waiting for its activity to settle or for its presentation record to be
 * saved. Main thread only.
 */
internal object PendingChatOpen {
    enum class Source { NOTIFICATION_TAP, HOST }

    private class Request(val source: Source, val stillWanted: () -> Boolean)

    private var pending: Request? = null

    /** Advances whenever routing turns off, so a tap's open asked for before that does not run after it. */
    private var routingGeneration = 0

    fun request(source: Source) {
        // A tap never replaces the host's own request, which routing must not cancel.
        if (pending?.source != Source.HOST) pending = Request(source, stillWanted(source))
        Log.debug { "Chat opens once an activity is resumed" }
    }

    /**
     * Opens the chat now for [source]; the presenter asks the answer again right before the sheet starts, after its
     * record was saved.
     */
    fun open(source: Source) = ReceptionChat.open(stillWanted(source))

    /** The host's own requests always stay wanted; a tap's only while routing has not turned off since it was made. */
    private fun stillWanted(source: Source): () -> Boolean {
        if (source == Source.HOST) return { true }
        val generation = routingGeneration
        return { generation == routingGeneration }
    }

    fun routingTurnedOff() {
        routingGeneration += 1
        if (pending?.source == Source.NOTIFICATION_TAP) pending = null
    }

    fun activityResumed(activity: Activity) {
        val request = pending ?: return
        pending = null
        // After the activity's own onResume, so the sheet starts over a settled screen.
        activity.window?.decorView?.post {
            if (!request.stillWanted()) return@post Log.debug { "Chat not opened, notification routing turned off" }
            if (ReceptionState.session != null) ReceptionChat.open(request.stillWanted)
        }
    }
}
