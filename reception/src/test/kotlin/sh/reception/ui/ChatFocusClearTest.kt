package sh.reception.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import sh.reception.ReceptionChatView
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness
import java.util.concurrent.TimeUnit

/**
 * Putting the keyboard away clears the composer's focus on an empty chat. Clearing a view's focus hands it at once to
 * the window's first focusable view before Android 9 and, from Android 9, outside touch mode (Robolectric's windows are
 * not in touch mode); Compose passed it on to the chat's only focusable, the composer, which kept the keyboard up.
 */
@RunWith(RobolectricTestRunner::class)
class ChatFocusClearTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    @Test
    @Config(sdk = [26])
    fun clearingFocusOnAnEmptyChatLeavesTheComposerUnfocusedBeforeAndroid9() = clearingFocusLeavesTheComposerUnfocused()

    @Test
    @Config(sdk = [34])
    fun clearingFocusOnAnEmptyChatLeavesTheComposerUnfocusedOutsideTouchMode() = clearingFocusLeavesTheComposerUnfocused()

    private fun clearingFocusLeavesTheComposerUnfocused() {
        harness.start()
        lateinit var focus: FocusManager
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        activity.setContent {
            focus = LocalFocusManager.current
            ReceptionChatView()
        }
        settle()
        val composition = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().single()

        composition.composer().config[SemanticsActions.RequestFocus].action?.invoke()
        settle()
        assertEquals("the composer took focus", true, composition.composer().config.getOrNull(SemanticsProperties.Focused))

        focus.clearFocus()
        settle()
        assertEquals("the composer kept focus", false, composition.composer().config.getOrNull(SemanticsProperties.Focused))
    }

    private fun ViewRootForTest.composer(): SemanticsNode = flatten(semanticsOwner.unmergedRootSemanticsNode)
        .single { it.config.getOrNull(SemanticsProperties.EditableText) != null }

    private fun settle() = repeat(3) {
        harness.settle()
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun views(view: View): List<View> = listOf(view) +
        ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())
}
