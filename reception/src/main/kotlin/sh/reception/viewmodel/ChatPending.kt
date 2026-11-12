package sh.reception.viewmodel

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.model.MessageStatus

/** A send that takes longer than this shows "Sending…"; a quick one goes straight to "Sent". */
private const val PENDING_DELAY_MILLIS = 400L

internal fun ChatModel.schedulePending(id: String) {
    clearPending(id)
    pendingTimers[id] = scope.launch {
        delay(PENDING_DELAY_MILLIS)
        if (!current || messages.none { it.id == id && it.status == MessageStatus.SENDING }) return@launch
        update(id) { it.copy(showsPending = true) }
    }
}

internal fun ChatModel.clearPending(id: String) {
    pendingTimers.remove(id)?.cancel()
    if (messages.any { it.id == id && it.showsPending }) update(id) { it.copy(showsPending = false) }
}
