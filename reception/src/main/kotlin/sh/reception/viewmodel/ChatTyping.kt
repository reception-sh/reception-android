package sh.reception.viewmodel

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import sh.reception.client.ReceptionAPI
import sh.reception.client.ReceptionAPIError
import sh.reception.client.hasConversation
import sh.reception.model.EmptyResponse

/** Typing stops being reported this long after the last keystroke. */
private const val TYPING_IDLE_MILLIS = 1_500L

/** While typing continues, the service hears it again at most this often. */
private const val TYPING_REPEAT_MILLIS = 3_000L

@Serializable
private class TypingInput(val typing: Boolean)

/** Tells the team whether the person is typing. Requests go out one after another, never overlapping. */
internal fun ChatModel.sendTyping(active: Boolean) {
    userTypingIdle?.cancel()
    userTypingIdle = null
    val session = session ?: return
    if (!current || !session.store.hasConversation || (active && !(isChatActive && canSend))) return
    if (active) {
        userTypingIdle = scope.launch {
            delay(TYPING_IDLE_MILLIS)
            sendTyping(false)
        }
        if (userIsTyping && System.currentTimeMillis() - lastUserTyping < TYPING_REPEAT_MILLIS) return
    } else if (!userIsTyping) {
        return
    }
    userIsTyping = active
    lastUserTyping = if (active) System.currentTimeMillis() else Long.MIN_VALUE
    val previous = userTypingTask
    userTypingTask = scope.launch {
        previous?.join()
        if (!current) return@launch
        try {
            session.api.request<EmptyResponse>("conversation/typing", "POST", authenticated = true,
                body = ReceptionAPI.encode(TypingInput(active)))
        } catch (error: ReceptionAPIError) {
            handleReset(error)
        }
    }
}
