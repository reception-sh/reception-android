package sh.reception.viewmodel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import sh.reception.ReceptionUnread
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.ReceptionAPI
import sh.reception.client.ReceptionAPIError
import sh.reception.client.cursor
import sh.reception.model.EmptyResponse
import sh.reception.model.Message

@Serializable
internal data class ReadCursor(val conversationId: String, val throughSeq: Long)

internal sealed interface ReadTarget {
    data class Bounded(val cursor: ReadCursor) : ReadTarget

    data object Legacy : ReadTarget
}

/** JavaScript's largest safe integer, the service's limit for sequences. */
private const val MAXIMUM_SEQUENCE = 9_007_199_254_740_991

@Serializable
private class BoundedReadResponse(val conversationId: String, val readSeq: Long, val unreadCount: Int)

@Serializable
private class UnreadCountResponse(val unreadCount: Int)

/** Rejected GETs coalesce into one current full load, after the rejected fetch has released `loading`. */
internal fun ChatModel.reloadAfterStaleGet() {
    if (reloadTask != null) return
    reloadTask = scope.launch {
        reloadTask = null
        if (!current || !isVisible || !ApplicationLifecycle.isActive) return@launch
        load()
    }
}

/** Only processed GET messages establish a read boundary; send acknowledgments never do. */
internal fun ChatModel.processedReadTarget(
    conversationId: String?,
    hasConversation: Boolean,
    boundedRead: Boolean?,
    messages: List<Message>,
): ReadTarget? {
    if (!hasConversation) {
        readCursor = null
        maxSeq = 0
        session?.store?.cursor = 0
        return null
    }
    if (boundedRead != true) {
        readCursor = null
        return ReadTarget.Legacy
    }
    if (conversationId.isNullOrEmpty() || !messages.all { (it.seq ?: -1) in 1..MAXIMUM_SEQUENCE }) {
        throw ReceptionAPIError("invalid_response", 0)
    }
    val previous = readCursor?.takeIf { it.conversationId == conversationId }?.throughSeq ?: 0
    val cursor = ReadCursor(conversationId, maxOf(previous, messages.mapNotNull { it.seq }.maxOrNull() ?: 0))
    readCursor = cursor
    maxSeq = cursor.throughSeq
    session?.store?.cursor = maxSeq
    return ReadTarget.Bounded(cursor)
}

internal suspend fun ChatModel.markRead(target: ReadTarget?) {
    val session = session ?: return
    if (target == null || !canApplyRead()) return
    val generation = ReceptionUnread.invalidateRead()
    val count = when (target) {
        is ReadTarget.Bounded -> {
            val cursor = target.cursor
            val response = session.api.request<BoundedReadResponse>("conversation/read", "POST", authenticated = true,
                body = ReceptionAPI.encode(cursor))
            if (response.conversationId != cursor.conversationId || response.readSeq < cursor.throughSeq ||
                response.readSeq > MAXIMUM_SEQUENCE || response.unreadCount < 0
            ) throw ReceptionAPIError("invalid_response", 0)
            if (readCursor != cursor) return
            response.unreadCount
        }
        ReadTarget.Legacy -> {
            // Older servers ignore read bodies. Preserve their explicit unbounded contract.
            session.api.request<EmptyResponse>("conversation/read", "POST", authenticated = true)
            if (!canApplyRead() || generation != ReceptionUnread.readGeneration) return
            val response = session.api.request<UnreadCountResponse>("conversation/unread", authenticated = true)
            if (response.unreadCount < 0) throw ReceptionAPIError("invalid_response", 0)
            response.unreadCount
        }
    }
    if (!canApplyRead()) return
    ReceptionUnread.noteRead(count, generation)
}

private suspend fun ChatModel.canApplyRead(): Boolean =
    current && isVisible && currentCoroutineContext().isActive && ApplicationLifecycle.isActive
