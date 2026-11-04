package sh.reception.viewmodel

import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.client.hasConversation
import sh.reception.client.hasStartedChat
import java.util.concurrent.TimeUnit

/** `data: reconnect` opens the next stream at once: no backoff, no failure counted. */
@RunWith(RobolectricTestRunner::class)
class ChatStreamTest {
    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    @Test
    fun aReconnectSignalOpensTheNextStreamWithoutBackoff() {
        val chat = harness.start()
        harness.session.store.hasStartedChat = true
        harness.session.store.hasConversation = true
        chat.markVisible(true)
        var streams = 0
        harness.respond = { request ->
            when (request.path?.substringBefore('?')) {
                "/v1/conversation/events" -> {
                    streams += 1
                    val events = MockResponse().setHeader("Content-Type", "text/event-stream")
                    // The first stream ends with the service's reconnect signal; the second stays open.
                    if (streams == 1) events.setBody(": ping\n\ndata: reconnect\n\n")
                    else events.setBody(": ping\n\n").setBodyDelay(3, TimeUnit.SECONDS)
                }
                "/v1/conversation/messages" -> harness.json("""{"messages":[]}""")
                "/v1/conversation/unread" -> harness.json("""{"hasConversation":true,"unreadCount":0}""")
                else -> null
            }
        }
        chat.startPolling()
        harness.mainLoopUntil { harness.requests("GET", "/v1/conversation/events").size == 2 }
        assertNull(harness.session.retries["stream"])
        chat.stopPolling()
    }
}
