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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness
import java.util.concurrent.TimeUnit

/**
 * TalkBack meets the SDK's own sheet as it met Material's: a pane titled with the chat's title that offers Dismiss,
 * and the scrim behind it as a "Close" button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatSheetAccessibilityTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    @Test
    fun theSheetIsAPaneWithTheChatTitleAndADismissAction() {
        harness.start()
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        activity.setContent { ChatSheet(dismissRequested = false, onDismissed = {}) }
        repeat(3) {
            harness.settle()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
        val nodes = semanticsNodes(activity.window.decorView)
        val panes = nodes.filter { it.config.getOrNull(SemanticsProperties.PaneTitle) != null }
        assertEquals(listOf("Support"), panes.map { it.config[SemanticsProperties.PaneTitle] })
        assertTrue(SemanticsActions.Dismiss in panes.single().config)
        val scrim = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Close") }
        assertTrue(SemanticsActions.OnClick in scrim.config)
    }

    private fun semanticsNodes(root: View): List<SemanticsNode> {
        val owner = views(root).filterIsInstance<ViewRootForTest>().single().semanticsOwner
        return flatten(owner.unmergedRootSemanticsNode)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun views(view: View): List<View> = listOf(view) +
        ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())
}
