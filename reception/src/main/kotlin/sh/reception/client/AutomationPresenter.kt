package sh.reception.client

import kotlinx.coroutines.suspendCancellableCoroutine
import sh.reception.ui.ChatPresenter
import kotlin.coroutines.resume

/** How an automation opens the chat; tests replace it. */
internal interface AutomationPresenter {
    /** The app is active and no activity is starting or closing: an activity is resumed. */
    val isSettled: Boolean

    /**
     * Presents the chat and returns once the sheet started (true) or the presentation was skipped or cancelled before
     * it started (false), for example because another activity resumed while its record was saved. [stillWanted] is
     * asked right before the sheet starts.
     */
    suspend fun present(stillWanted: () -> Boolean): Boolean

    object Chat : AutomationPresenter {
        override val isSettled: Boolean
            get() = ApplicationLifecycle.isActive && ApplicationLifecycle.currentActivity != null

        override suspend fun present(stillWanted: () -> Boolean): Boolean = suspendCancellableCoroutine { continuation ->
            ChatPresenter.present(stillWanted) { started -> continuation.resume(started) }
        }
    }
}
