package sh.reception.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import sh.reception.ReceptionChatView
import sh.reception.model.Attachment
import sh.reception.model.Message
import sh.reception.model.MessageStatus
import sh.reception.viewmodel.ChatHarness
import sh.reception.viewmodel.ChatModel
import java.util.concurrent.TimeUnit

/**
 * Every screen of the chat composes and lays out. The SDK is compiled against one Compose version and runs on the
 * host's; tool/release_check.sh runs these tests once more on the newest Compose BOM, where a call into a signature
 * Compose removed fails here with NoSuchMethodError instead of in a customer's app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatScreensTest {
    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    private val photo = Attachment(id = "a1", url = "https://example.com/a1.jpg", contentType = "image/jpeg",
        width = 400, height = 300)

    private fun message(id: String, sender: String = "AGENT", text: String = "Hello", kind: String = "TEXT") =
        Message(id = id, seq = id.removePrefix("m").toLong(), sender = sender, text = text, attachments = emptyList(),
            createdAt = 1_700_000_000_000 + id.removePrefix("m").toLong(), kind = kind)

    /** One message of every kind the history shows, ending in a failed outgoing one with its Retry line. */
    private val messages = listOf(
        message("m1"),
        message("m2").copy(attachments = listOf(photo)),
        message("m3", kind = "REVIEW").copy(reviewUrl = "https://example.com/review"),
        message("m4", kind = "LINK").copy(linkUrl = "https://example.com/pricing"),
        message("m5", kind = "PAYWALL").copy(paywallId = "pro", paywallTitle = "Pro"),
        message("m6", sender = "USER"),
        message("m7", sender = "USER", text = "Not sent").copy(seq = null, status = MessageStatus.FAILED),
    )

    private fun start(): ChatModel = harness.start().also { it.messages = messages }

    /** Composes [content] in an activity, runs its effects and layout, and fails unless it was placed. */
    private fun composes(content: @Composable () -> Unit) {
        var placed = false
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            Box(Modifier.onPlaced { placed = true }) { content() }
        }
        repeat(3) {
            harness.settle()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
        assertTrue("not laid out", placed)
    }

    @Test
    fun theEmbeddedChatComposes() {
        start()
        composes { ReceptionChatView() }
    }

    @Test
    fun theSheetComposes() {
        start()
        composes { ChatSheet(dismissRequested = false, onDismissed = {}) }
    }

    @Test
    fun theHistoryComposes() {
        val model = start()
        composes { ChatRoot(embedded = true) { ChatHistory(model, topInset = 0.dp, requestNotifications = {}) } }
    }

    @Test
    fun theComposerComposes() {
        val model = start()
        composes { ChatRoot(embedded = true) { Composer(model, onFocus = {}) } }
    }

    @Test
    fun theCardsCompose() {
        start()
        composes {
            ChatRoot(embedded = true) {
                ReviewCard(messages[2], openReview = {})
                LinkCard(messages[3], isInApp = false, openLink = {})
                PaywallCard(messages[4], isAvailable = true, openPaywall = {})
            }
        }
    }

    @Test
    fun theLightboxComposes() {
        start()
        composes { ChatRoot(embedded = true) { ImageLightbox(photo, onDismiss = {}) } }
    }
}
