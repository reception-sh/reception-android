package sh.reception.viewmodel

import android.os.Looper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import sh.reception.Reception
import sh.reception.ReceptionAutomations
import sh.reception.ReceptionLifecycle
import sh.reception.ReceptionUnread
import sh.reception.Theme
import sh.reception.testing.MainLooperDrain
import sh.reception.viewmodel.ChatHarness.Companion.CONVERSATION
import sh.reception.viewmodel.ChatHarness.Companion.message
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A chat open on its welcome screen, before anyone wrote, learns that its conversation exists: an automation's message,
 * a scheduled automation's message found at the next foreground, a push for the team's first reply, the person's own
 * first send. Each time
 * the open chat loads it and then listens on the live stream, so the team's next reply reaches it; a close meanwhile
 * ends the load and the stream.
 */
@RunWith(RobolectricTestRunner::class)
class AutomationInOpenChatTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    /** Holds the conversation's first load and unread answer, so the network watch answers before them. */
    private val answers = harness.gate()

    /** Holds the team's next reply until the test has seen what came before it. */
    private val reply = harness.gate()

    /** The incremental polls so far: the stream's first poll finds nothing new, the next ones find the reply `m2`. */
    private val polls = AtomicInteger()

    @Volatile
    private var conversation = false

    @Volatile
    private var scheduled = false

    @After
    fun tearDown() = harness.stop()

    /** The service: one conversation once [conversation] holds, with `m1`; the stream says "changed" and `m2` follows. */
    private fun respond(request: RecordedRequest): MockResponse? = when (request.path?.substringBefore('?')) {
        "/v1/automations" -> harness.json("""{"events":["welcome_event"]}""")
        "/v1/automations/events" ->
            if (scheduled) {
                harness.json("""{"sent":false,"scheduled":true,"openChat":false}""")
            } else {
                conversation = true
                harness.json("""{"sent":true,"openChat":false}""")
            }
        "/v1/conversation/unread" -> held { harness.json("""{"hasConversation":$conversation,"unreadCount":0}""") }
        "/v1/conversation" -> held {
            if (conversation) harness.json("""{"conversation":$CONVERSATION,"messages":[${message("m1", 1)}]}""")
            else harness.json("""{"conversation":null,"messages":[]}""")
        }
        // "changed" comes a moment after the stream opens, once the load that started it has ended.
        "/v1/conversation/events" -> MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(": ping\n\ndata: changed\n\n").setBodyDelay(200, TimeUnit.MILLISECONDS)
        "/v1/conversation/messages" ->
            if (polls.incrementAndGet() == 1) {
                harness.json("""{"messages":[]}""")
            } else {
                reply.answer { harness.json("""{"conversation":$CONVERSATION,"messages":[${message("m2", 2)}]}""") }
            }
        else -> null
    }

    private fun held(response: () -> MockResponse): MockResponse =
        if (conversation) answers.answer(response) else response()

    private fun openOnWelcomeScreen(): ChatModel {
        harness.respond = ::respond
        val chat = harness.start()
        chat.markVisible(true)
        chat.activate()
        harness.mainLoopUntil { chat.isChatActive }
        harness.mainLoopUntil { ReceptionUnread.runningRefresh == null }
        assertTrue(chat.messages.isEmpty())
        return chat
    }

    /** The first load is under way and the network watch, if running, has answered; then the service answers. */
    private fun answerAfterTheNetworkWatch(chat: ChatModel) {
        harness.mainLoopUntil {
            harness.requests("GET", "/v1/conversation").isNotEmpty() && answers.held > 0 &&
                (chat.networkMonitor == null || chat.pathSatisfied != null)
        }
        assertTrue("the first load is still on its way", chat.fetching && chat.messages.isEmpty())
        answers.open()
    }

    /** The chat listens: its stream said "changed" and the poll that follows waits for the reply, which then comes. */
    private fun releaseTheReplyOnceTheChatListens(chat: ChatModel) {
        harness.mainLoopUntil { reply.held == 1 }
        assertTrue("the reply shows only once it is released", chat.messages.none { it.id == "m2" })
        reply.open()
    }

    private fun assertListensAndShowsTheNextReply(chat: ChatModel) {
        releaseTheReplyOnceTheChatListens(chat)
        harness.mainLoopUntil { chat.messages.any { it.id == "m2" } }
        assertEquals(listOf("m1", "m2"), chat.messages.map { it.id })
        chat.deactivate(keepsPolling = false)
    }

    @Test
    fun anAutomationsMessageAppearsAndTheChatListens() {
        val chat = openOnWelcomeScreen()
        ReceptionAutomations.automations.track("welcome_event", opensChat = false, session = harness.session)
        answerAfterTheNetworkWatch(chat)
        harness.mainLoopUntil { chat.isTyping }
        assertTrue("the message waits behind the typing dots", chat.messages.isEmpty())
        // The dots show for Theme.AUTOMATION_TYPING_MILLIS of the main looper's clock.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Theme.AUTOMATION_TYPING_MILLIS))
        harness.mainLoopUntil { chat.messages.isNotEmpty() }
        assertEquals(listOf("m1"), chat.messages.map { it.id })
        assertListensAndShowsTheNextReply(chat)
    }

    @Test
    fun aScheduledAutomationsMessageIsFoundAtTheNextForegroundNotWhileTheChatStaysOpen() {
        scheduled = true
        val chat = openOnWelcomeScreen()
        ReceptionAutomations.automations.track("welcome_event", opensChat = false, session = harness.session)
        harness.mainLoopUntil { harness.requests("GET", "/v1/conversation").isNotEmpty() && !chat.fetching }
        assertTrue(chat.messages.isEmpty())

        // The message's time came while the chat stays open and no push reaches the device: as on iOS and in the
        // shared SDK specification, nothing asks for it then.
        conversation = true
        answers.open()
        val paths = listOf("/v1/conversation/unread", "/v1/conversation", "/v1/conversation/events")
        val asked = { paths.sumOf { harness.requests("GET", it).size } }
        val before = asked()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        harness.settle()
        assertTrue(chat.messages.isEmpty())
        assertEquals("nothing asks for the conversation", before, asked())

        // The next foreground's unread refresh finds the conversation; the open chat loads it and listens.
        ReceptionLifecycle.applicationBecameActive()
        harness.mainLoopUntil { chat.messages.isNotEmpty() }
        assertListensAndShowsTheNextReply(chat)
    }

    @Test
    fun aPushForTheTeamsFirstReplyLoadsItAndTheChatListens() {
        val chat = openOnWelcomeScreen()
        conversation = true
        assertTrue(Reception.handlePushNotification(mapOf("reception" to "c1"), openChat = false))
        answerAfterTheNetworkWatch(chat)
        harness.mainLoopUntil { chat.messages.isNotEmpty() }
        assertListensAndShowsTheNextReply(chat)
    }

    @Test
    fun thePersonsFirstMessageStartsTheStream() {
        val chat = openOnWelcomeScreen()
        val respond = harness.respond
        harness.respond = { request ->
            if (request.method == "POST" && request.path == "/v1/conversation/messages") {
                conversation = true
                answers.open()
                harness.json("""{"conversation":$CONVERSATION,"message":${message("m1", 1, sender = "USER")}}""", 201)
            } else {
                respond(request)
            }
        }
        chat.send("Hello", emptyList())
        releaseTheReplyOnceTheChatListens(chat)
        harness.mainLoopUntil { chat.messages.any { it.id == "m2" } }
        chat.deactivate(keepsPolling = false)
    }

    @Test
    fun aCloseWhileTheMessageIsRevealedEndsTheLoadAndTheStream() {
        val chat = openOnWelcomeScreen()
        ReceptionAutomations.automations.track("welcome_event", opensChat = false, session = harness.session)
        answerAfterTheNetworkWatch(chat)
        harness.mainLoopUntil { chat.isTyping }
        chat.deactivate(keepsPolling = false)
        chat.markVisible(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Theme.AUTOMATION_TYPING_MILLIS))
        harness.settle()
        assertTrue("the closed chat shows nothing it loaded", chat.messages.isEmpty())
        assertFalse(chat.isTyping)
        assertEquals(0, harness.requests("GET", "/v1/conversation/events").size)
    }
}
