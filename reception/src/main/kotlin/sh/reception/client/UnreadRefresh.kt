package sh.reception.client

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import sh.reception.Log
import sh.reception.model.Timestamp
import sh.reception.ReceptionState
import kotlin.coroutines.cancellation.CancellationException

@Serializable
private class UnreadResponse(
    val hasConversation: Boolean,
    val unreadCount: Int,
    val status: String? = null,
    @Serializable(with = Timestamp::class) val lastMessageAt: Long? = null,
    val push: Boolean? = null,
    val unreadPolling: List<UnreadStage>? = null,
)

/**
 * The iOS `ChatModel.refreshUnread`: an optional device update, then `GET /v1/conversation/unread`. What depends on
 * the chat screen (its visibility, loading when the team wrote first, sending after a probe) goes through
 * `ReceptionState.chat`, which the chat model provides.
 */
internal suspend fun DeviceSession.refreshUnread(updateDevice: Boolean = false, probe: Boolean = false) {
    if (!isCurrent || (halt != null && !probe) || !isRegistered || (!probe && retries["unread"]?.stopped == true)) return
    val started = store.hasStartedChat
    try {
        if (!probe) retries["unread"]?.delay?.let { delay(it) }
        if (updateDevice && !probe) update()
        val generation = ReceptionState.readGeneration
        val response = withContext(RequestContext.current().copy(probe = if (probe) RequestProbe() else null)) {
            api.request<UnreadResponse>("conversation/unread", authenticated = true)
        }
        retries.remove("unread")
        if (probe) ReceptionState.chat?.sendingResumed()
        if (!isCurrent || !currentCoroutineContext().isActive) return
        store.hasConversation = response.hasConversation
        store.conversationStatus = response.status
        store.lastMessageAt = response.lastMessageAt
        store.pushActive = response.push
        store.unreadPolling = response.unreadPolling
        // A read invalidates older badge responses, even after the chat closes.
        if (generation == ReceptionState.readGeneration && ReceptionState.chat?.isVisible != true) {
            ReceptionState.setUnread(response.unreadCount)
        }
        Log.debug { "Unread refresh, ${response.unreadCount} unread" }
        // The team wrote first: a chat that is already open starts loading and listening now.
        if (!started && response.hasConversation) ReceptionState.chat?.conversationStarted()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        if (error is ReceptionAPIError && error.code != "cancelled" && error.code != "session_halted" && !error.isCooldown) {
            if (probe && error.status == 403 && error.code == "blocked") {
                Log.debug { "Request skipped, session halted" }
            } else {
                Log.error { "Unread refresh failed, HTTP ${error.status} ${error.code}" }
            }
        }
        if (halt == null) retries.getOrPut("unread") { RetryState() }.failed(error)
        ReceptionState.handleReset(this, error)
    } finally {
        if (isCurrent && currentCoroutineContext().isActive) ReceptionState.unreadMonitor.reschedule()
    }
}
