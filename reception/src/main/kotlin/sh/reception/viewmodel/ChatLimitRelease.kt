package sh.reception.viewmodel

import sh.reception.client.RetryScope
import sh.reception.client.RetryState
import sh.reception.model.MessageStatus

/** Conversation messages and sending permission have already been merged, so acknowledged IDs cannot resend. */
internal fun ChatModel.resumeLimitHeldMessages() {
    val session = session ?: return
    if (!current || !isWatched || !canSend || verificationRequired || session.halt != null) return
    messages = messages.map { message ->
        if (message.status != MessageStatus.FAILED || !message.retryAfterLimitRelease || cooldown(message) != null) {
            return@map message
        }
        session.retries[message.id] = RetryState()
        limitReleaseRetries += message.id
        message.copy(awaitsManualRetry = false, heldBy = message.heldBy ?: RetryScope.MESSAGES)
    }
    if (limitReleaseRetries.isEmpty()) return
    saveMessages()
    cooldownsChanged()
}

/** The existing retry driver drains the released IDs. Any new failure or departure ends that batch. */
internal fun ChatModel.pauseLimitReleaseRetries() {
    if (limitReleaseRetries.isEmpty()) return
    messages = messages.map {
        if (it.id in limitReleaseRetries && it.seq == null) it.copy(awaitsManualRetry = true) else it
    }
    limitReleaseRetries.clear()
    saveMessages()
}
