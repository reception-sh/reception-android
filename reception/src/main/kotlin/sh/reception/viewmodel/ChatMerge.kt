package sh.reception.viewmodel

import sh.reception.ReceptionResets
import sh.reception.client.cursor
import sh.reception.model.Message
import sh.reception.model.MessageStatus

/**
 * Merges messages from the service into the history: a known sequence or client ID replaces the local copy (which
 * keeps its ID and, until the service confirms attachments, its photos); anything else is appended. Only GETs
 * advance the cursor.
 */
internal fun ChatModel.merge(incoming: List<Message>, advancesCursor: Boolean = true) {
    val merged = messages.toMutableList()
    for (message in incoming) {
        val index = merged.indexOfFirst { existing ->
            (message.seq != null && existing.seq == message.seq) ||
                (message.clientId != null && (message.clientId == existing.id || message.clientId == existing.clientId))
        }
        if (index >= 0) {
            val existing = merged[index]
            var updated = message.copy(id = existing.id, status = MessageStatus.SENT)
            if (!updated.hasConfirmedAttachments) {
                updated = updated.copy(images = existing.images, attachmentIds = existing.attachmentIds)
            }
            clearPending(updated.id)
            merged[index] = updated.copy(showsPending = false)
        } else {
            merged += message
            if (message.sender == "AGENT") setTyping(false)
        }
    }
    messages = ordered(merged)
    if (advancesCursor) {
        maxSeq = maxOf(maxSeq, incoming.mapNotNull { it.seq }.maxOrNull() ?: 0)
        session?.store?.cursor = maxSeq
    }
    if (incoming.isNotEmpty()) saveMessages()
}

/**
 * Sequence order where both messages have one, else creation time, as iOS sorts. That rule is not transitive across
 * sent and unsent messages, which `sortedWith` may reject with an exception, so this is a stable insertion sort.
 */
private fun ordered(messages: List<Message>): List<Message> {
    val result = ArrayList<Message>(messages.size)
    for (message in messages) {
        var index = result.size
        while (index > 0 && precedes(message, result[index - 1])) index -= 1
        result.add(index, message)
    }
    return result
}

private fun precedes(left: Message, right: Message): Boolean {
    val leftSeq = left.seq
    val rightSeq = right.seq
    return if (leftSeq != null && rightSeq != null) leftSeq < rightSeq else left.createdAt < right.createdAt
}

/** A `409 chat_reset` from this chat's session clears the chat; every other failure is recorded on the session. */
internal fun ChatModel.handleReset(error: Throwable): Boolean {
    val session = session ?: return false
    if (!current) return false
    return ReceptionResets.handleReset(session, error)
}
