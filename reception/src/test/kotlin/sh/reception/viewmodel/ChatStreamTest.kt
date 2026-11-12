package sh.reception.viewmodel

import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import sh.reception.client.RetryState
import sh.reception.client.hasConversation
import sh.reception.client.hasStartedChat
import sh.reception.testing.MainLooperDrain
import sh.reception.viewmodel.ChatHarness.Companion.CONVERSATION
import sh.reception.viewmodel.ChatHarness.Companion.message
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `data: reconnect` opens the next stream at once after a healthy stream: no backoff, no failure counted; a stream asked
 * to reconnect right after opening backs off. A "changed" signal while another GET runs is not lost.
 */
@RunWith(RobolectricTestRunner::class)
class ChatStreamTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    @Test
    fun aReconnectSignalOpensTheNextStreamWithoutBackoff() {
        val chat = harness.start()
        // The service rotates a stream that has been up for a while; here every stream counts as that old.
        chat.healthyStreamMillis = 0
        harness.session.store.hasStartedChat = true
        harness.session.store.hasConversation = true
        chat.markVisible(true)
        val streams = AtomicInteger()
        // The second stream stays open until the test ends.
        val second = harness.gate()
        harness.respond = { request ->
            when (request.path?.substringBefore('?')) {
                "/v1/conversation/events" -> {
                    val events = MockResponse().setHeader("Content-Type", "text/event-stream")
                    // The first stream ends with the service's reconnect signal.
                    if (streams.incrementAndGet() == 1) events.setBody(": ping\n\ndata: reconnect\n\n")
                    else second.answer { events.setBody(": ping\n\n") }
                }
                "/v1/conversation/messages" -> harness.json("""{"messages":[]}""")
                "/v1/conversation/unread" -> harness.json("""{"hasConversation":true,"unreadCount":0}""")
                else -> null
            }
        }
        chat.startPolling()
        harness.mainLoopUntil { second.held == 1 }
        assertEquals(2, harness.requests("GET", "/v1/conversation/events").size)
        assertNull(harness.session.retries["stream"])
        chat.stopPolling()
    }

    @Test
    fun streamsAskedToReconnectAtOnceBackOffLikeFailures() {
        val chat = harness.start()
        harness.session.store.hasStartedChat = true
        harness.session.store.hasConversation = true
        chat.markVisible(true)
        harness.respond = { request ->
            when (request.path?.substringBefore('?')) {
                // A broken server or proxy: every stream asks for the next one as soon as it opens, on a network slow
                // enough that the answer takes longer than any fixed allowance of real time would wait for it.
                "/v1/conversation/events" -> MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setHeadersDelay(SLOW_NETWORK_MILLIS, TimeUnit.MILLISECONDS).setBody("data: reconnect\n\n")
                "/v1/conversation/messages" -> harness.json("""{"messages":[]}""")
                "/v1/conversation/unread" -> harness.json("""{"hasConversation":true,"unreadCount":0}""")
                else -> null
            }
        }
        // The stream's backoff counts on the main looper's clock, which its wait is scheduled on, not in real time.
        val retry = RetryState(now = SystemClock::uptimeMillis)
        harness.session.retries["stream"] = retry
        chat.startPolling()
        val start = SystemClock.uptimeMillis()
        var opens = 0
        while (true) {
            opens += 1
            // The stream's answer arrived and its end counted as a failure; the main looper's clock stood still
            // meanwhile, so no stream looks older than it is and no backoff ran early.
            harness.mainLoopUntil { opens().size == opens && retry.attempts == opens }
            // The test thread may be descheduled for a while here, as on a busy machine: the clock still stands.
            if (opens == 1) Thread.sleep(PAUSE_MILLIS)
            val backoff = checkNotNull(retry.delay)
            if (SystemClock.uptimeMillis() + backoff - start >= 60_000) break
            // Exactly when the stream's wait ends: it was scheduled for this many milliseconds from now.
            shadowOf(Looper.getMainLooper()).idleFor(backoff, TimeUnit.MILLISECONDS)
        }
        // The ordinary backoff (2, 4, 8, 16, 32 s, with jitter) allows at most six opens in a minute, not hundreds.
        assertEquals(opens, opens().size)
        assertTrue("$opens streams opened in a minute", opens in 2..6)
        chat.stopPolling()
    }

    private fun opens() = harness.requests("GET", "/v1/conversation/events")

    @Test
    fun aChangedSignalDuringALoadPollsOnceTheLoadEnds() {
        val chat = harness.start()
        harness.session.store.hasStartedChat = true
        harness.session.store.hasConversation = true
        chat.markVisible(true)
        chat.isChatActive = true
        val loadAnswer = harness.gate()
        harness.respond = { request ->
            when (request.path?.substringBefore('?')) {
                "/v1/conversation" -> loadAnswer.answer {
                    harness.json("""{"conversation":$CONVERSATION,"messages":[${message("m1", 1)}]}""")
                }
                "/v1/conversation/messages" -> harness.json("""{"messages":[${message("m2", 2)}]}""")
                else -> null
            }
        }
        chat.scope.launch { chat.load() }
        harness.mainLoopUntil { loadAnswer.held == 1 }
        // The stream says "changed" while the load waits for an answer the service may have built before the change.
        chat.scope.launch { chat.poll() }
        harness.settle()
        assertEquals("the load is still on its way", 1, loadAnswer.held)
        assertTrue("the poll waits for the load", polls().isEmpty())
        loadAnswer.open()
        harness.mainLoopUntil { chat.messages.any { it.id == "m2" } }
        assertEquals(listOf("m1", "m2"), chat.messages.map { it.id })
        chat.isChatActive = false
    }

    private companion object {
        const val SLOW_NETWORK_MILLIS = 300L

        /** Longer than the first backoff (2 s with ±25 % jitter). */
        const val PAUSE_MILLIS = 3_000L
    }

    private fun polls() = harness.requests.toList().filter { it.path?.startsWith("/v1/conversation/messages") == true }
}
