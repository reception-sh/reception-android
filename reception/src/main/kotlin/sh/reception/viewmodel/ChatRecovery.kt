package sh.reception.viewmodel

import sh.reception.client.ReceptionAPIError
import sh.reception.client.RetryState
import sh.reception.model.MessageStatus

/** Quota waits retain their separate release/cancel policy; older failed caches stay manual. */
internal fun isRecoverableSubmissionFailure(error: Throwable): Boolean {
    if (error is ReceptionAPIError && (error.isCooldown || error.status == 429 || error.retryScope != null)) return false
    return ReceptionAPIError.isTransient(error)
}

/** Restore missing process-local budgets only. Reopening must not renew an active or spent budget. */
internal fun ChatModel.resumeTransientFailures() {
    val session = session ?: return
    if (!current || !isWatched || !canSend || verificationRequired || session.halt != null) return
    for (message in messages) {
        if (message.status == MessageStatus.FAILED && message.retryAfterTransientFailure && !message.awaitsManualRetry &&
            cooldown(message)?.automatic != false && session.retries[message.id] == null
        ) session.retries[message.id] = RetryState()
    }
    retryFailedAutomatically()
}
