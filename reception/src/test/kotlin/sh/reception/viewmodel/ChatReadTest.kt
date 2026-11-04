package sh.reception.viewmodel

import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.ReceptionUnread
import sh.reception.client.hasStartedChat
import sh.reception.viewmodel.ChatHarness.Companion.CONVERSATION
import sh.reception.viewmodel.ChatHarness.Companion.message
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read generations reject obsolete GETs; a bounded read acknowledges exactly the processed boundary. */
@RunWith(RobolectricTestRunner::class)
class ChatReadTest {
    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    private fun conversation(vararg messages: String) =
        """{"boundedRead":true,"conversation":$CONVERSATION,"messages":[${messages.joinToString(",")}]}"""

    @Test
    fun aGetOvertakenByARead_isDroppedAndReloaded() {
        val chat = harness.start()
        harness.session.store.hasStartedChat = true
        chat.markVisible(true)
        val release = CountDownLatch(1)
        var gets = 0
        harness.respond = { request ->
            when (request.path) {
                "/v1/conversation" -> {
                    gets += 1
                    if (gets == 1) {
                        release.await(5, TimeUnit.SECONDS)
                        harness.json(conversation(message("stale", 1)))
                    } else {
                        harness.json(conversation(message("stale", 1), message("fresh", 2)))
                    }
                }
                "/v1/conversation/read" -> harness.json("""{"conversationId":"c1","readSeq":2,"unreadCount":0}""")
                else -> null
            }
        }
        chat.scope.launch { chat.load() }
        harness.mainLoopUntil { harness.requests("GET", "/v1/conversation").isNotEmpty() }
        // A send confirmation lands while the GET is in flight.
        ReceptionUnread.invalidateRead()
        release.countDown()
        harness.mainLoopUntil { harness.requests("POST", "/v1/conversation/read").isNotEmpty() }
        assertEquals(2, harness.requests("GET", "/v1/conversation").size)
        assertEquals(listOf("stale", "fresh"), chat.messages.map { it.id })
        assertEquals(1, harness.requests("POST", "/v1/conversation/read").size)
    }

    @Test
    fun aBoundedReadSendsTheConversationAndTheHighestProcessedSequence() {
        val chat = harness.start()
        harness.session.store.hasStartedChat = true
        chat.markVisible(true)
        ReceptionUnread.set(4)
        harness.respond = { request ->
            when (request.path) {
                "/v1/conversation" -> harness.json(conversation(message("m1", 1), message("m3", 3)))
                "/v1/conversation/read" -> harness.json("""{"conversationId":"c1","readSeq":3,"unreadCount":1}""")
                else -> null
            }
        }
        chat.scope.launch { chat.load() }
        harness.mainLoopUntil { ReceptionUnread.count.value == 1 }
        val read = harness.requests("POST", "/v1/conversation/read").single()
        assertEquals("""{"conversationId":"c1","throughSeq":3}""", read.body.readUtf8())
    }
}
