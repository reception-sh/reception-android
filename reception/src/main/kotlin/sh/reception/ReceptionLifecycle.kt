package sh.reception

import sh.reception.viewmodel.startStream

/** What the app's foreground and background and a resumed session start and stop. Main thread only. */
internal object ReceptionLifecycle {
    fun applicationBecameActive() {
        ReceptionAppearance.becameActive()
        ReceptionPaywalls.refresh()
        ReceptionState.unreadMonitor.becameActive()
        ReceptionState.logoutRevocations?.becameActive()
        if (ReceptionState.session != null) {
            ReceptionAutomations.checkSubscriptions()
            ReceptionPush.registerIfAuthorized()
        }
    }

    fun applicationEnteredBackground() {
        ReceptionAppearance.enteredBackground()
        ReceptionState.unreadMonitor.enteredBackground()
        ReceptionState.logoutRevocations?.suspend()
    }

    fun sessionResumed() {
        val session = ReceptionState.session ?: return
        ReceptionState.chat?.sendingResumed()
        ReceptionState.logoutRevocations?.resumed(session.api)
        ReceptionAppearance.sessionResumed()
        ReceptionState.unreadMonitor.reschedule()
        ReceptionState.chat?.startStream()
    }
}
