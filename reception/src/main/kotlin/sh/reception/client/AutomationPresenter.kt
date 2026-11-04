package sh.reception.client

import sh.reception.ui.ChatPresenter

/** How an automation opens the chat; tests replace it. */
internal interface AutomationPresenter {
    /** The app is active and no activity is starting or closing: an activity is resumed. */
    val isSettled: Boolean

    /** Presents the chat; false when nothing was presented. */
    fun present(): Boolean

    object Chat : AutomationPresenter {
        override val isSettled: Boolean
            get() = ApplicationLifecycle.isActive && ApplicationLifecycle.currentActivity != null

        override fun present(): Boolean = ChatPresenter.present()
    }
}
