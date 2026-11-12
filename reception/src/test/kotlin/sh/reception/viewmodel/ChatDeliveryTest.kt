package sh.reception.viewmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.ReceptionUnread
import sh.reception.client.hasConversation
import sh.reception.model.Message
import sh.reception.model.MessageStatus
import sh.reception.testing.MainLooperDrain
import sh.reception.viewmodel.ChatHarness.Companion.CONVERSATION
import sh.reception.viewmodel.ChatHarness.Companion.message

/** Optimistic sending: the client ID survives confirmation and every retry; a rejected upload mapping is dropped. */
@RunWith(RobolectricTestRunner::class)
class ChatDeliveryTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    private fun posts() = harness.requests("POST", "/v1/conversation/messages")
        .map { Json.parseToJsonElement(it.body.readUtf8()).jsonObject }

    private fun confirmation(clientId: String) =
        harness.json("""{"conversation":$CONVERSATION,"message":${message("server-1", 1, "USER", clientId, "Hello")}}""", 201)

    @Test
    fun aConfirmedMessageKeepsItsClientIdAndIsSent() {
        val chat = harness.start()
        harness.respond = { request ->
            if (request.method == "POST" && request.path == "/v1/conversation/messages") {
                val clientId = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["clientId"]?.jsonPrimitive?.content
                confirmation(requireNotNull(clientId))
            } else null
        }
        chat.send("  Hello ", emptyList())
        val id = chat.messages.single().id
        harness.mainLoopUntil { chat.messages.single().status == MessageStatus.SENT }
        val sent = chat.messages.single()
        assertEquals(id, sent.id)
        assertEquals(1L, sent.seq)
        val body = posts().single()
        assertEquals(id, body["clientId"]?.jsonPrimitive?.content)
        assertEquals("Hello", body["text"]?.jsonPrimitive?.content)
        assertEquals(JsonArray(emptyList()), body["attachmentIds"])
    }

    @Test
    fun anUnreadAnswerAskedBeforeTheFirstSendDoesNotTakeTheConversationBack() {
        val chat = harness.start()
        harness.mainLoopUntil { ReceptionUnread.runningRefresh == null }
        val answer = harness.gate()
        harness.respond = { request ->
            when {
                request.path == "/v1/conversation/unread" ->
                    answer.answer { harness.json("""{"hasConversation":false,"unreadCount":0}""") }
                request.method == "POST" && request.path == "/v1/conversation/messages" -> {
                    val clientId = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["clientId"]?.jsonPrimitive?.content
                    confirmation(requireNotNull(clientId))
                }
                else -> null
            }
        }
        val unread = { harness.requests("GET", "/v1/conversation/unread").size }
        val earlier = unread()
        ReceptionUnread.refresh()
        harness.mainLoopUntil { answer.held == 1 }
        chat.send("Hello", emptyList())
        harness.mainLoopUntil { chat.messages.single().status == MessageStatus.SENT }
        assertEquals("every unread answer asked since is still on its way", unread() - earlier, answer.held)
        answer.open()
        harness.mainLoopUntil { ReceptionUnread.runningRefresh == null }
        assertTrue("the answer from before the send is older", harness.session.store.hasConversation)
    }

    @Test
    fun aFailedMessageOffersRetryAndRetriesWithTheSameId() {
        val chat = harness.start()
        var fail = true
        harness.respond = { request ->
            if (request.method == "POST" && request.path == "/v1/conversation/messages") {
                if (fail) harness.error(500, "server_error") else confirmation(chat.messages.first().id)
            } else null
        }
        chat.send("Hello", emptyList())
        val id = chat.messages.single().id
        harness.mainLoopUntil { chat.messages.single().status == MessageStatus.FAILED }
        assertEquals(RetryAvailability.Now, chat.retryAvailability(chat.messages.single()))
        fail = false
        chat.retry(id)
        harness.mainLoopUntil { chat.messages.single().status == MessageStatus.SENT }
        assertEquals(listOf(id, id), posts().map { it["clientId"]?.jsonPrimitive?.content })
        assertEquals(id, chat.messages.single().id)
    }

    @Test
    fun anIncompleteUploadDiscardsOnlyTheUploadIds() {
        val chat = harness.start()
        harness.respond = { request ->
            if (request.method == "POST" && request.path == "/v1/conversation/messages") harness.error(409, "upload_incomplete")
            else null
        }
        val photo = byteArrayOf(1, 2, 3)
        chat.messages = listOf(Message(id = "C0FFEE00-0000-4000-8000-000000000001", sender = "USER", text = "Look",
            attachments = emptyList(), createdAt = System.currentTimeMillis(), status = MessageStatus.FAILED,
            images = listOf(photo), attachmentIds = listOf("attachment-1"), expectedImageCount = 1))
        chat.retry("C0FFEE00-0000-4000-8000-000000000001")
        harness.mainLoopUntil { chat.messages.single().status == MessageStatus.FAILED && harness.requests.any { it.path == "/v1/conversation/messages" } }
        val failed = chat.messages.single()
        assertEquals(listOf("attachment-1"), posts().single()["attachmentIds"]?.let { (it as JsonArray).map { id -> id.jsonPrimitive.content } })
        assertEquals(emptyList<String>(), failed.attachmentIds)
        assertTrue(failed.images.single().contentEquals(photo))
        assertEquals("Look", failed.text)
        assertEquals("C0FFEE00-0000-4000-8000-000000000001", failed.id)
    }
}
