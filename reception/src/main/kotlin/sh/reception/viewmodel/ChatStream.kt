package sh.reception.viewmodel

import android.os.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.DeviceSession
import sh.reception.client.EventStream
import sh.reception.client.ReceptionAPIError
import sh.reception.client.RetryState
import sh.reception.client.hasConversation
import sh.reception.client.stream

/** Quiet chats are confirmed with one incremental GET around once a minute, as iOS does on its 20-second pings. */
private const val QUIET_REFRESH_MILLIS = 60_000L
private const val TYPING_TIMEOUT_MILLIS = 8_000L

/**
 * The live stream while the chat is active: `changed` polls, `typing`/`idle` drive the indicator, `reconnect` opens
 * the next stream at once (the service closes streams with their access token). Failures back off; after eight in a
 * row it keeps trying at [ChatModel.exhaustedStreamRetryMillis] while the chat stays open.
 */
internal fun ChatModel.startStream() {
    val session = session ?: return
    if (!isChatActive || !current || !session.store.hasConversation || session.halt != null || polling != null) return
    val id = Any()
    streamId = id
    polling = scope.launch {
        try {
            stream(session)
        } finally {
            if (streamId === id) {
                polling = null
                streamId = null
                streamConnected = false
            }
        }
    }
}

private suspend fun ChatModel.stream(session: DeviceSession) {
    while (currentCoroutineContext().isActive && current && isChatActive && session.store.hasConversation &&
        session.halt == null
    ) {
        val retry = session.retries["stream"]
        if (retry?.stopped == true) {
            delay(exhaustedStreamRetryMillis)
            session.retries.remove("stream")
        } else {
            retry?.delay?.let { delay(it) }
        }
        if (session.halt != null || !currentCoroutineContext().isActive) return
        var failure: Throwable = ReceptionAPIError("connection_failed", 0, networkError = "StreamEnded")
        var reconnect = false
        var connected: Long? = null
        var stream: EventStream? = null
        try {
            if (!isStreaming()) return
            val opened = session.api.stream("conversation/events")
            stream = opened
            if (!isStreaming()) return
            streamDidConnect()
            connected = SystemClock.elapsedRealtime()
            reconnect = read(opened)
            if (!isStreaming()) return
        } catch (error: ReceptionAPIError) {
            failure = error
            if (!currentCoroutineContext().isActive || !current) return
            if (handleReset(error) || error.code == "cancelled") return
            if (error.code == "no_conversation") {
                session.store.hasConversation = false
                setTyping(false)
                return
            }
        } finally {
            stream?.close()
        }
        if (!currentCoroutineContext().isActive || !current) return
        if (reconnect) continue
        streamDidDisconnect()
        if (session.halt != null) return
        // A stream that stayed up had recovered: its drop starts a fresh backoff instead of adding to old failures.
        if (connected != null && SystemClock.elapsedRealtime() - connected >= healthyStreamMillis) {
            session.retries.remove("stream")
        }
        session.retries.getOrPut("stream") { RetryState() }.failed(failure)
    }
}

/** Reads signals until the stream ends; true when the service asked for a new stream. */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun ChatModel.read(stream: EventStream): Boolean {
    var lastRefresh = SystemClock.elapsedRealtime()
    poll()
    while (true) {
        val quiet = QUIET_REFRESH_MILLIS - (SystemClock.elapsedRealtime() - lastRefresh)
        val event = select {
            stream.events.onReceiveCatching { it }
            onTimeout(maxOf(0, quiet)) { null }
        }
        if (!isStreaming()) return false
        if (event == null) {
            lastRefresh = SystemClock.elapsedRealtime()
            poll()
            continue
        }
        if (event.isClosed) {
            event.exceptionOrNull()?.let { throw it }
            return false
        }
        when (event.getOrNull()) {
            "changed" -> {
                lastRefresh = SystemClock.elapsedRealtime()
                poll()
            }
            "typing" -> setTyping(true)
            "idle" -> setTyping(false)
            "reconnect" -> return true
        }
    }
}

private suspend fun ChatModel.isStreaming(): Boolean =
    currentCoroutineContext().isActive && current && isChatActive && ApplicationLifecycle.isActive

internal fun ChatModel.cancelStream() {
    streamId = null
    polling?.cancel()
    polling = null
    streamConnected = false
}

internal fun ChatModel.setTyping(typing: Boolean) {
    typingDismissal?.cancel()
    typingDismissal = null
    isTyping = typing
    if (!typing) return
    typingDismissal = scope.launch {
        delay(TYPING_TIMEOUT_MILLIS)
        setTyping(false)
    }
}
