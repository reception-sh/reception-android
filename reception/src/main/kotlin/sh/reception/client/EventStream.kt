package sh.reception.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * An open `text/event-stream`: the `data` of each event in order. The channel closes when the service ends the
 * stream and fails with a [ReceptionAPIError] when the connection breaks. [close] ends it from this side.
 */
internal class EventStream(val events: ReceiveChannel<String>, private val source: EventSource) {
    fun close() = source.cancel()
}

/**
 * The live stream of [path] with the session bookkeeping of [ReceptionAPI.request]: cooldowns, the token, one renewal
 * after a rejected token, halts and probes.
 */
internal suspend fun ReceptionAPI.stream(path: String): EventStream {
    val scopes = RetryScope.gates(path, "GET", authenticated = true)
    val session = session ?: throw ReceptionAPIError("cancelled", 0)
    session.prepared()
    session.admit(scopes)
    val token = session.token()
    val probe = session.authorizeRequest(scopes)
    try {
        val stream = try {
            openStream(path, token)
        } catch (error: ReceptionAPIError) {
            if (!error.rejectsToken) throw error
            val renewed = session.renewedToken(replacing = token)
            session.admit(scopes)
            openStream(path, renewed)
        }
        session.requestSucceeded(probe)
        return stream
    } catch (error: ReceptionAPIError) {
        session.requestFailed(error, path, "GET")
        throw error
    }
}

/** Pings arrive every 20 s; a stream silent for longer than this has broken. */
private val streamClient: OkHttpClient by lazy {
    HttpExchange.client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
}

private suspend fun ReceptionAPI.openStream(path: String, token: String): EventStream {
    val request = httpRequest(path, token).header("Accept", "text/event-stream").get().build()
    val events = Channel<String>(Channel.UNLIMITED)
    val opened = CompletableDeferred<Unit>()
    val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            opened.complete(Unit)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            events.trySend(data)
        }

        override fun onClosed(eventSource: EventSource) {
            events.close()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val error = failure(path, t, response)
            if (!opened.completeExceptionally(error)) events.close(error)
        }
    }
    val source = EventSources.createFactory(streamClient).newEventSource(request, listener)
    try {
        opened.await()
    } catch (cancellation: CancellationException) {
        source.cancel()
        throw cancellation
    }
    return EventStream(events, source)
}

/** Runs on OkHttp's thread while [response]'s body is still readable. */
private fun failure(path: String, cause: Throwable?, response: Response?): ReceptionAPIError {
    if (response == null || response.isSuccessful) {
        return ReceptionAPIError("connection_failed", 0, networkError = (cause ?: IOException()).javaClass.simpleName)
    }
    ReceptionAPI.logRateLimit(response.code, response.headers)
    val body = try {
        response.body?.string().orEmpty()
    } catch (_: IOException) {
        ""
    }
    val envelope = ErrorEnvelope.parse(ReceptionAPI.json, body)
    val wait = ReceptionAPI.wait(response.code, response.headers["Retry-After"], envelope?.retryScope, path, "GET", true)
    return ReceptionAPIError(envelope?.code ?: "http_error", response.code, envelope?.message.orEmpty(),
        envelope?.resetRevision, retryAfter = wait.seconds, retryScope = wait.scope)
}
