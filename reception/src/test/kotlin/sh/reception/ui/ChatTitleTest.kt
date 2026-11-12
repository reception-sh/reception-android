package sh.reception.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import sh.reception.Reception
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness
import java.util.concurrent.TimeUnit

/** The sheet's title stays on one centered line clear of the close button and ends in an ellipsis when too long (A24-5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Real text measurement: the legacy graphics measure every title as short.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatTitleTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() {
        Reception.appearance = Reception.Appearance()
        harness.stop()
    }

    private class Header(val title: SemanticsNode, val close: SemanticsNode?, val pane: SemanticsNode) {
        val layout: TextLayoutResult
            get() = mutableListOf<TextLayoutResult>().also { title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.single()
    }

    private fun header(title: String, fontScale: Float, closeButton: Boolean = true): Header {
        harness.start()
        RuntimeEnvironment.setFontScale(fontScale)
        Reception.appearance = Reception.Appearance().apply {
            this.title = title
            showsCloseButton = closeButton
        }
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        activity.setContent { ChatSheet(dismissRequested = false, onDismissed = {}) }
        repeat(5) {
            harness.settle()
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        }
        val root = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().single()
        val nodes = flatten(root.semanticsOwner.unmergedRootSemanticsNode)
        val pane = nodes.single { it.config.getOrNull(SemanticsProperties.PaneTitle) != null }
        // The scrim around the sheet closes it too; the button is the small one.
        val closes = nodes.filter { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Close") }
        return Header(
            title = nodes.single { it.config.getOrNull(SemanticsProperties.Heading) != null },
            close = closes.singleOrNull { it.boundsInRoot.width < pane.boundsInRoot.width / 2 },
            pane = pane,
        )
    }

    private fun assertCenteredClearOfTheCloseButton(header: Header) {
        val title = header.title.boundsInRoot
        val close = checkNotNull(header.close).boundsInRoot
        assertTrue("title $title under the close button $close", title.right <= close.left)
        val pane = header.pane.boundsInRoot
        assertEquals("centered in the sheet", pane.center.x, title.center.x, 1f)
    }

    private fun assertALongTitleEndsInAnEllipsisBeforeTheCloseButton(fontScale: Float) {
        val header = header(LONG_TITLE, fontScale)
        assertCenteredClearOfTheCloseButton(header)
        assertEquals("one line", 1, header.layout.lineCount)
        assertTrue("ellipsis", header.layout.isLineEllipsized(0))
    }

    @Test
    fun aLongTitleEndsInAnEllipsisBeforeTheCloseButton() = assertALongTitleEndsInAnEllipsisBeforeTheCloseButton(1f)

    @Test
    fun aLongTitleEndsInAnEllipsisBeforeTheCloseButtonAtTheLargestFontScale() =
        assertALongTitleEndsInAnEllipsisBeforeTheCloseButton(2f)

    @Test
    fun aShortTitleShowsWhole() {
        val header = header("Support", fontScale = 2f)
        assertCenteredClearOfTheCloseButton(header)
        assertFalse(header.layout.isLineEllipsized(0))
    }

    @Test
    fun withoutTheCloseButtonALongTitleKeepsTheMarginsOnly() {
        val header = header(LONG_TITLE, fontScale = 1f, closeButton = false)
        assertEquals(null, header.close)
        val pane = header.pane.boundsInRoot
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val margin = titleSideInset(showsCloseButton = false).value * density
        assertEquals(pane.left + margin, header.title.boundsInRoot.left, 1f)
        assertEquals(pane.right - margin, header.title.boundsInRoot.right, 1f)
    }

    private companion object {
        const val LONG_TITLE = "Customer support for all your questions about orders and billing"
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun views(view: View): List<View> = listOf(view) +
        ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())
}
