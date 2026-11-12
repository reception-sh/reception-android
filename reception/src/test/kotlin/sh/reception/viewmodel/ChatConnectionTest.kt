package sh.reception.viewmodel

import android.os.Looper
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import sh.reception.client.hasConversation
import sh.reception.client.hasStartedChat
import sh.reception.testing.MainLooperDrain
import sh.reception.viewmodel.ChatHarness.Companion.CONVERSATION
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The connection banner of a chat used before, with the network up. Its 1 s grace counts from when the chat listens: an
 * opening that takes longer (an automation's message is revealed after its typing dots before the chat listens, fixes
 * 26 F1) shows no banner, while a stream that does not come up shows "Connecting" after the grace.
 */
@RunWith(RobolectricTestRunner::class)
class ChatConnectionTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    /** Opens a chat used before whose opening load waits at [load] and whose stream answers with [events]. */
    private fun openChat(load: ResponseGate, events: () -> MockResponse): ChatModel {
        val chat = harness.start()
        harness.session.store.hasStartedChat = true
        harness.session.store.hasConversation = true
        harness.respond = { request ->
            when (request.path?.substringBefore('?')) {
                "/v1/conversation" -> load.answer { harness.json("""{"conversation":$CONVERSATION,"messages":[]}""") }
                "/v1/conversation/events" -> events()
                "/v1/conversation/messages" -> harness.json("""{"messages":[]}""")
                "/v1/conversation/unread" -> harness.json("""{"hasConversation":true,"unreadCount":0}""")
                else -> null
            }
        }
        chat.markVisible(true)
        chat.activate()
        harness.mainLoopUntil { load.held == 1 && chat.pathSatisfied == true }
        return chat
    }

    private fun waitOnTheMainLooper(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun anOpeningLongerThanTheGraceShowsNoBanner() {
        val load = harness.gate()
        // A stream that opens and stays open for seconds: its body trickles in, a byte every 250 ms.
        val chat = openChat(load) {
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": ping\n\n".repeat(4))
                .throttleBody(1, 250, TimeUnit.MILLISECONDS)
        }
        waitOnTheMainLooper(OPENING_MILLIS)
        assertEquals(ConnectionState.HIDDEN, chat.connection)
        load.open()
        harness.mainLoopUntil { chat.streamConnected }
        assertEquals("nothing was down, so nothing reconnected", ConnectionState.HIDDEN, chat.connection)
        chat.deactivate(keepsPolling = false)
    }

    @Test
    fun aStreamThatDoesNotComeUpShowsConnectingAfterTheGrace() {
        val load = harness.gate()
        val stream = harness.gate()
        val chat = openChat(load) {
            stream.answer { MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": ping\n\n") }
        }
        waitOnTheMainLooper(OPENING_MILLIS)
        load.open()
        harness.mainLoopUntil { chat.isChatActive && stream.held == 1 }
        assertEquals(ConnectionState.HIDDEN, chat.connection)
        waitOnTheMainLooper(GRACE_MILLIS)
        assertEquals(ConnectionState.CONNECTING, chat.connection)
        chat.deactivate(keepsPolling = false)
    }

    private companion object {
        /** An opening that outlasts the grace, as an automation's reveal does on top of the opening load. */
        const val OPENING_MILLIS = 2_000L
        const val GRACE_MILLIS = 1_100L
    }
}
