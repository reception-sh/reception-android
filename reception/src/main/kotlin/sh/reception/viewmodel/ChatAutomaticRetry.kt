package sh.reception.viewmodel

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.model.MessageStatus

internal class AutomaticRetry(val messageId: String, val id: Any, val task: Job)

/** Resends one failed message at a time, oldest first, so a queue keeps its order and never races itself. */
internal fun ChatModel.retryFailedAutomatically() {
    val session = session ?: return
    if (!canSend || verificationRequired || !isWatched || automaticRetry != null || session.halt != null) return
    val start = messages.indexOfFirst { retriesAutomatically(it) && sends[it.id] == null }
    // Never while an earlier message is still in flight: it would be overtaken.
    if (start < 0 || messages.take(start).any { sends[it.id] != null }) return
    val messageId = messages[start].id
    val retryId = Any()
    val task = scope.launch {
        while (canSend && !verificationRequired && session.halt == null) {
            val index = messages.indexOfFirst { it.id == messageId }
            if (index < 0 || !retriesAutomatically(messages[index])) break
            val cooldown = cooldown(messages[index])
            if (cooldown?.automatic == false) {
                // A longer wait arrived meanwhile: sending later is the person's decision.
                update(messageId) { it.copy(awaitsManualRetry = true) }
                saveMessages()
                break
            }
            val wait = listOfNotNull(session.retries[messageId]?.delay,
                cooldown?.let { maxOf(0, it.until - session.cooldowns.now()) }).maxOrNull()
            if (wait != null && wait > 0) {
                delay(wait)
                continue
            }
            val earlier = messages.take(index).firstNotNullOfOrNull { sends[it.id] }
            if (earlier != null) {
                earlier.join()
                continue
            }
            if (sends[messageId] != null) break
            update(messageId) { it.copy(status = MessageStatus.SENDING) }
            submit(messageId)
            sends[messageId]?.join()
        }
        if (automaticRetry?.id !== retryId) return@launch
        automaticRetry = null
        retryFailedAutomatically()
    }
    automaticRetry = AutomaticRetry(messageId, retryId, task)
}
