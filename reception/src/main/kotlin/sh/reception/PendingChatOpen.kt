package sh.reception

import android.app.Activity
import sh.reception.ui.ChatPresenter

/**
 * A chat that opens over the next activity that resumes, after a tap on one of the SDK's notifications. Turning the
 * SDK's routing off cancels it, also once it waits for its activity to settle. Main thread only.
 */
internal object PendingChatOpen {
    private var pending = false

    /** Advances whenever routing turns off, so an open queued before that does not run after it. */
    private var routingGeneration = 0

    fun request() {
        pending = true
        Log.debug { "Chat opens once an activity is resumed" }
    }

    fun routingTurnedOff() {
        routingGeneration += 1
        pending = false
    }

    fun activityResumed(activity: Activity) {
        if (!pending) return
        pending = false
        val generation = routingGeneration
        // After the activity's own onResume, so the sheet starts over a settled screen.
        activity.window?.decorView?.post {
            if (generation != routingGeneration) return@post Log.debug { "Chat not opened, notification routing turned off" }
            if (ReceptionState.session != null) ChatPresenter.open()
        }
    }
}
