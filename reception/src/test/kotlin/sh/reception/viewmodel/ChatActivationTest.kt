package sh.reception.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.client.hasConversation
import sh.reception.client.hasStartedChat
import sh.reception.viewmodel.ChatHarness.Companion.CONVERSATION
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A chat dismissed while its opening load is in flight neither listens nor polls afterwards. */
@RunWith(RobolectricTestRunner::class)
class ChatActivationTest {
    private val harness = ChatHarness()
    private val release = CountDownLatch(1)

    @After
    fun tearDown() {
        release.countDown()
        harness.stop()
    }

    private fun startedChat(): ChatModel {
        val chat = harness.start()
        harness.session.store.hasStartedChat = true
        harness.session.store.hasConversation = true
        harness.respond = { request ->
            when (request.path?.substringBefore('?')) {
                "/v1/conversation" -> {
                    release.await(5, TimeUnit.SECONDS)
                    harness.json("""{"conversation":$CONVERSATION,"messages":[]}""")
                }
                "/v1/conversation/events" -> harness.json("{}")
                else -> null
            }
        }
        chat.markVisible(true)
        return chat
    }

    @Test
    fun aChatDismissedDuringItsOpeningLoadDoesNotStartListening() {
        val chat = startedChat()
        chat.activate()
        harness.mainLoopUntil { harness.requests("GET", "/v1/conversation").isNotEmpty() }
        chat.deactivate(keepsPolling = false)
        release.countDown()
        val until = System.currentTimeMillis() + 500
        harness.mainLoopUntil { System.currentTimeMillis() > until }
        assertFalse(chat.isChatActive)
        assertEquals(0, harness.requests("GET", "/v1/conversation/events").size)
    }

    @Test
    fun anActiveChatListensOnceItsOpeningLoadFinishes() {
        val chat = startedChat()
        chat.activate()
        release.countDown()
        // A session resume may open the stream on its own, so the test waits for the activation itself.
        harness.mainLoopUntil { chat.isChatActive && harness.requests("GET", "/v1/conversation/events").isNotEmpty() }
        chat.deactivate(keepsPolling = false)
    }
}
