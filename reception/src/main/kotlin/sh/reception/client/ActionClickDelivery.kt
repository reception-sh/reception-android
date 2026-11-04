package sh.reception.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import sh.reception.ReceptionState
import sh.reception.model.EmptyResponse
import kotlin.coroutines.cancellation.CancellationException

/** What became of a link card tap; review and paywall receipts carry none. */
internal enum class ActionOutcome(val raw: String) { HANDLED("handled"), OPENED("opened"), FAILED("failed") }

internal fun DeviceSession.recordActionClick(messageId: String, outcome: ActionOutcome? = null) = afterPrepared {
    if (messageId !in store.pendingActionClicks) store.pendingActionClicks = store.pendingActionClicks + messageId
    // A queued success is not replaced by a later failure; the service keeps the better result too.
    if (outcome != null && (outcome != ActionOutcome.FAILED || store.actionClickOutcomes[messageId] == null)) {
        store.actionClickOutcomes = store.actionClickOutcomes + (messageId to outcome.raw)
    }
    flushActionClicks()
}

@Serializable
private class ActionClickPayload(val messageId: String, val outcome: String? = null)

internal fun DeviceSession.flushActionClicks() {
    if (invalidated || halt != null || actionClickTask != null || store.pendingActionClicks.isEmpty() || !isRegistered) {
        return
    }
    lateinit var task: Job
    task = scope.launch(start = CoroutineStart.LAZY) {
        try {
            while (!invalidated && halt == null) {
                val messageId = store.pendingActionClicks.firstOrNull() ?: return@launch
                val key = "action:$messageId"
                if (retries[key]?.stopped == true) return@launch
                retries[key]?.delay?.let { delay(it) }
                if (halt != null) return@launch
                val outcome = store.actionClickOutcomes[messageId]
                try {
                    api.request<EmptyResponse>("conversation/action-click", "POST", authenticated = true,
                        body = ReceptionAPI.encode(ActionClickPayload(messageId, outcome)))
                    if (invalidated) return@launch
                    actionClickFinished(messageId, outcome)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    if (error is ReceptionAPIError && error.status == 404 && error.code == "action_not_found") {
                        if (invalidated) return@launch
                        actionClickFinished(messageId, outcome)
                        continue
                    }
                    if (!invalidated) ReceptionState.handleReset(this@flushActionClicks, error)
                    if (invalidated || halt != null) return@launch
                    retries.getOrPut(key) { RetryState() }.failed(error)
                    return@launch
                }
            }
        } finally {
            if (actionClickTask === task) actionClickTask = null
        }
    }
    actionClickTask = task
    task.start()
}

/** A tap recorded while the request was in flight keeps the entry, so its newer outcome is sent next. */
private fun DeviceSession.actionClickFinished(messageId: String, outcome: String?) {
    if (store.actionClickOutcomes[messageId] != outcome) return
    store.pendingActionClicks = store.pendingActionClicks - messageId
    store.actionClickOutcomes = store.actionClickOutcomes - messageId
}
