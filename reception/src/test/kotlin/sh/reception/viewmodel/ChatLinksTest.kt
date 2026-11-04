package sh.reception.viewmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.ReceptionEvent
import sh.reception.ReceptionScreen
import sh.reception.ReceptionSettings
import sh.reception.ReceptionState
import sh.reception.model.Message
import sh.reception.ui.LinkOpener

/** Link card taps: the path taken, the events, and the outcome each receipt reports. */
@RunWith(RobolectricTestRunner::class)
class ChatLinksTest {
    private val harness = ChatHarness()
    private val events = mutableListOf<ReceptionEvent>()
    private var closed = 0
    private var opened = mutableListOf<String>()

    @After
    fun tearDown() {
        ReceptionSettings.setScreens(emptyList())
        ReceptionSettings.opensWebLinksInApp = true
        ReceptionState.onEvent = null
        harness.stop()
    }

    private fun chat(external: Boolean = true, webPage: Boolean = true): ChatModel {
        val chat = harness.start()
        ReceptionState.onEvent = { events += it }
        chat.links = LinkHandling(
            closePresentedChat = { next -> closed += 1; next() },
            openExternal = { opened += "external $it"; external },
            openInClaimingApp = { false },
            presentWebLink = { url, _ -> opened += "tab $url"; webPage },
            isHostScheme = { it.startsWith("myapp:") },
        )
        return chat
    }

    private fun receipt(): Pair<String?, String?> {
        harness.mainLoopUntil { harness.requests("POST", "/v1/conversation/action-click").isNotEmpty() }
        val body = Json.parseToJsonElement(harness.requests("POST", "/v1/conversation/action-click").single().body.readUtf8())
            .jsonObject
        return body["messageId"]?.jsonPrimitive?.content to body["outcome"]?.jsonPrimitive?.content
    }

    @Test
    fun aScreenLinkClosesTheChatAndRunsTheScreenWithItsValue() {
        val chat = chat()
        var value: String? = null
        ReceptionSettings.setScreens(listOf(ReceptionScreen("order", value = "Order number") { value = it }))
        chat.openLink("reception-screen:order?value=42", "m1")
        assertEquals(1, closed)
        assertEquals("42", value)
        assertEquals(listOf<ReceptionEvent>(ReceptionEvent.LinkOpened), events)
        assertEquals("m1" to "handled", receipt())
    }

    @Test
    fun aScreenTheAppNoLongerListsFails() {
        val chat = chat()
        chat.openLink("reception-screen:gone", "m2")
        assertEquals("m2" to "failed", receipt())
    }

    @Test
    fun aHostLinkClosesTheChatThenOpensIt() {
        val chat = chat()
        chat.openLink("myapp://orders/7", "m3")
        assertEquals(1, closed)
        assertEquals(listOf("external myapp://orders/7"), opened)
        assertEquals("m3" to "opened", receipt())
    }

    @Test
    fun aWebLinkOpensOverTheOpenChat() {
        val chat = chat()
        chat.openLink("https://example.com/help", "m4")
        assertEquals(0, closed)
        assertEquals(listOf("tab https://example.com/help"), opened)
        assertEquals("m4" to "opened", receipt())
    }

    @Test
    fun aLinkNoAppOpensFails() {
        val chat = chat(external = false)
        ReceptionSettings.opensWebLinksInApp = false
        chat.openLink("https://example.com/help", "m5")
        assertEquals(listOf("external https://example.com/help"), opened)
        assertEquals("m5" to "failed", receipt())
    }

    @Test
    fun cardButtonsShowTheAgentsLabelOrTheLocalizedDefault() {
        fun card(kind: String, label: String? = null) =
            Message(id = "m", sender = "AGENT", text = "", attachments = emptyList(), createdAt = 0, kind = kind, buttonLabel = label)
        assertEquals("Rate on Google Play", card("REVIEW").cardLabel)
        assertEquals("View offer", card("PAYWALL").cardLabel)
        assertEquals("Open link", card("LINK").cardLabel)
        assertEquals("  Exactly as sent ", card("LINK", "  Exactly as sent ").cardLabel)
    }

    @Test
    fun aPlayStoreListingPrefersThePlayStoreApp() {
        assertEquals("market://details?id=sh.reception.app",
            LinkOpener.playStoreListing("https://play.google.com/store/apps/details?id=sh.reception.app&hl=de"))
        assertEquals(null, LinkOpener.playStoreListing("https://play.google.com/store/search?q=x"))
        val chat = chat()
        assertTrue(chat.isHostLink("reception-screen:settings"))
        assertTrue(chat.isHostLink("myapp://orders/7"))
        assertFalse(chat.isHostLink("https://example.com"))
    }
}
