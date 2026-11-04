package sh.reception.viewmodel

import kotlinx.coroutines.launch
import sh.reception.ReceptionUnread
import sh.reception.client.hasStartedChat
import sh.reception.model.MessageStatus

// The active chat (iOS `ChatModel` start/stopPolling): while the chat is resumed it listens on the stream, watches the
// network, submits pending sends and retries failed ones; stopping ends all of it.

internal fun ChatModel.startPolling() {
    isChatActive = true
    val session = session ?: return
    if (!isRestored || !session.store.hasStartedChat) return
    // Opening the chat or sending a message is a fresh start for a stream that gave up, as a foreground is.
    session.retries["stream"]?.foreground()
    startStream()
    for (message in messages) if (message.status == MessageStatus.SENDING) submit(message.id)
    startMonitoring()
    retryFailedAutomatically()
}

internal fun ChatModel.stopPolling() {
    isChatActive = false
    reloadTask?.cancel()
    reloadTask = null
    if (current) ReceptionUnread.invalidateRead()
    sendTyping(false)
    cancelStream()
    setTyping(false)
    linkGrace?.cancel()
    linkGrace = null
    stopMonitoring()
    cancelAutomaticRetry()
    deferCooldownRetries()
    connectionDismissal?.cancel()
    if (connection != ConnectionState.OFFLINE) connection = ConnectionState.HIDDEN
}

/** The team wrote first: a chat that is already open starts loading and listening now. */
internal fun ChatModel.conversationStarted() {
    if (!isChatActive) return
    startPolling()
    scope.launch { load() }
}
