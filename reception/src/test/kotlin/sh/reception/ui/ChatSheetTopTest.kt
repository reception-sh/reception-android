package sh.reception.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
 * The sheet starts below the whole safe area at the window's top edge, never under a cutout or caption bar, and its
 * content stays out of a cutout at the window's sides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatSheetTopTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    @Test
    fun theSheetStartsBelowTheWholeSafeAreaAtTheWindowsTop() {
        harness.start()
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        // Edge to edge, as the chat's activity: the window's insets reach the content.
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.setContent { ChatSheet(dismissRequested = false, onDismissed = {}) }
        settle()
        val composition = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().single()

        // A cutout that reaches lower than the status bar (seen on an Android 16 emulator: 74 against 132).
        assertEquals(132, composition.sheetTop(statusBar = 74, cutout = 132, caption = 0))
        // The usual portrait case, the status bar covers the cutout.
        assertEquals(132, composition.sheetTop(statusBar = 132, cutout = 132, caption = 0))
        // Landscape, the cutout at the side.
        assertEquals(74, composition.sheetTop(statusBar = 74, cutout = 0, caption = 0))
        // A caption bar (a freeform window) without a status bar.
        assertEquals(96, composition.sheetTop(statusBar = 0, cutout = 0, caption = 96))
    }

    @Test
    fun inAWindowNarrowerThanTheSheetItsContentStaysOutOfTheSideCutouts() {
        harness.start()
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.setContent { ChatSheet(dismissRequested = false, onDismissed = {}) }
        settle()
        val composition = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().single()
        val width = composition.view.width
        assertTrue("the window is narrower than the sheet's maximum", width < 640)

        for ((left, right) in listOf(132 to 0, 0 to 96)) {
            composition.apply(WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(left, 0, right, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(), true)
                .build())
            val pane = composition.pane()
            // The surface reaches under the cutout, as next to the system bars; what it shows stays beside it.
            assertEquals(0f, pane.boundsInRoot.left)
            assertEquals(width.toFloat(), pane.boundsInRoot.right)
            val content = flatten(pane).drop(1).map { it.boundsInRoot }.filter { it.width > 0f }
            assertTrue("nothing to check", content.isNotEmpty())
            assertTrue("content under the left cutout $left: $content", content.all { it.left >= left })
            assertTrue("content under the right cutout $right: $content", content.all { it.right <= width - right })
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h400dp-mdpi")
    fun inAWideWindowTheSheetSitsCenteredBetweenTheSideInsets() {
        harness.start()
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.setContent { ChatSheet(dismissRequested = false, onDismissed = {}) }
        settle()
        val composition = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().single()
        assertEquals(720, composition.view.width)

        // A 640 px sheet centered on the whole window (40…680) would reach 20 px under a 60 px side navigation bar
        // or cutout; between the insets it has room, so it sits centered there and the scrim shows on both sides.
        for ((left, right) in listOf(0 to 60, 60 to 0)) {
            composition.apply(WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, right, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(left, 0, 0, 0))
                .setVisible(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(), true)
                .build())
            val pane = composition.pane().boundsInRoot
            assertEquals("left edge with insets $left/$right", left + 10f, pane.left)
            assertEquals("right edge with insets $left/$right", 720f - right - 10f, pane.right)
        }

        // A camera cutout that does not reach the centered sheet leaves it centered on the window.
        composition.apply(WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(30, 0, 0, 0))
            .setVisible(WindowInsetsCompat.Type.displayCutout(), true)
            .build())
        assertEquals("left edge beside a 30 px cutout", 40f, composition.pane().boundsInRoot.left)
    }

    /** The sheet's top edge when open, in window pixels, once its window has these insets at its top. */
    private fun ViewRootForTest.sheetTop(statusBar: Int, cutout: Int, caption: Int): Int {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, cutout, 0, 0))
            .setInsets(WindowInsetsCompat.Type.captionBar(), Insets.of(0, caption, 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.captionBar(), true)
            .build()
        apply(insets)
        // Full height below its top padding; the height does not depend on how far the slide in has come.
        return view.height - pane().size.height
    }

    private fun ViewRootForTest.apply(insets: WindowInsetsCompat) {
        ViewCompat.dispatchApplyWindowInsets(view, insets)
        // Robolectric does not draw, and Compose lays out an inset change while drawing: a View layout runs it.
        view.requestLayout()
        settle()
    }

    /** The sheet itself, the node with the pane title. */
    private fun ViewRootForTest.pane(): SemanticsNode = flatten(semanticsOwner.unmergedRootSemanticsNode)
        .single { it.config.getOrNull(SemanticsProperties.PaneTitle) != null }

    /** Past the sheet's 300 ms slide in. */
    private fun settle() = repeat(5) {
        harness.settle()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun views(view: View): List<View> = listOf(view) +
        ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())
}
