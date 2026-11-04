package sh.reception

import android.app.Activity
import sh.reception.ui.ChatPresenter

/**
 * A chat that opens over the next activity that resumes: after a tap on one of the SDK's notifications, or the host's
 * own `handlePushNotification(openChat = true)` while no activity is resumed. Turning the SDK's routing off cancels
 * only the taps' requests, also one already waiting for its activity to settle. Main thread only.
 */
internal object PendingChatOpen {
    enum class Source { NOTIFICATION_TAP, HOST }

    private var pending: Source? = null

    /** Advances whenever routing turns off, so a tap's open queued before that does not run after it. */
    private var routingGeneration = 0

    fun request(source: Source) {
        // A tap never replaces the host's own request, which routing must not cancel.
        if (pending != Source.HOST) pending = source
        Log.debug { "Chat opens once an activity is resumed" }
    }

    fun routingTurnedOff() {
        routingGeneration += 1
        if (pending == Source.NOTIFICATION_TAP) pending = null
    }

    fun activityResumed(activity: Activity) {
        val source = pending ?: return
        pending = null
        val generation = routingGeneration
        // After the activity's own onResume, so the sheet starts over a settled screen.
        activity.window?.decorView?.post {
            if (source == Source.NOTIFICATION_TAP && generation != routingGeneration) {
                return@post Log.debug { "Chat not opened, notification routing turned off" }
            }
            if (ReceptionState.session != null) ChatPresenter.open()
        }
    }
}
