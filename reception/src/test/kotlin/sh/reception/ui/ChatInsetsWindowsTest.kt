package sh.reception.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
import java.util.concurrent.TimeUnit

/**
 * A chat measures its insets against the window that shows it now, also after a retained composition moved into
 * another window, and pads in its own pixels, also when the host scales it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatInsetsWindowsTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    @After
    fun tearDown() = TestActivities.closeAll()

    private var imeBottom = -1

    /**
     * A chat whose composition outlives detaching, recording its IME inset ([InsetsProbe]). It carries the activity's
     * owners itself, so a move into another window finds the same owners and recomposes nothing.
     */
    private fun retainedChat(activity: ComponentActivity): ComposeView = ComposeView(activity).apply {
        setViewTreeLifecycleOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(activity))
        setContent { InsetsProbe() }
    }

    /** A chat area that records its IME inset at each layout. */
    @Composable
    private fun InsetsProbe() {
        ChatInsetsArea(Modifier.fillMaxSize()) {
            val insets = chatInsets()
            Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                imeBottom = insets.ime.getBottom(this)
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            })
        }
    }

    /** The keyboard covers the bottom [bottom] pixels of the window that shows [chat]. */
    private fun showKeyboard(chat: ComposeView, bottom: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        // Straight to the composition's own view, where Compose listens for insets.
        val composition = chat.getChildAt(0)
        ViewCompat.dispatchApplyWindowInsets(composition, insets)
        // Robolectric does not draw, and Compose lays out an inset change while drawing: a View layout runs it.
        composition.requestLayout()
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    }

    private val View.bottomInWindow: Int
        get() = IntArray(2).also(::getLocationInWindow)[1] + height

    @Test
    fun aRetainedChatMovedIntoADialogMeasuresAgainstTheDialogsWindow() {
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        val chat = retainedChat(activity)
        // In the activity, the chat sits at the top, well above the window's bottom edge.
        val host = FrameLayout(activity)
        host.addView(chat, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200, Gravity.TOP))
        activity.setContentView(host)
        ShadowLooper.idleMainLooper()
        val activityDistance = chat.rootView.height - chat.bottomInWindow
        showKeyboard(chat, bottom = activityDistance + 150)
        assertEquals(150, imeBottom)

        // In a dialog the chat ends nearer its window's bottom edge, which the keyboard overlap is measured from now.
        host.removeView(chat)
        val dialog = ComponentDialog(activity)
        dialog.setContentView(chat, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200))
        TestActivities.show(dialog)
        ShadowLooper.idleMainLooper()
        val dialogDistance = chat.rootView.height - chat.bottomInWindow
        assertTrue(dialogDistance < activityDistance)
        showKeyboard(chat, bottom = dialogDistance + 150)
        assertEquals(150, imeBottom)
        dialog.dismiss()
    }

    @Test
    fun aChatTheHostScalesPadsSoThatItsComposerStillClearsTheKeyboard() {
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        val chat = ComposeView(activity).apply {
            setContent {
                // Half its height, around its bottom edge, which stays on the window's bottom edge.
                Box(Modifier.fillMaxSize().graphicsLayer { scaleY = 0.5f; transformOrigin = TransformOrigin(0.5f, 1f) }) {
                    InsetsProbe()
                }
            }
        }
        activity.setContentView(chat)
        ShadowLooper.idleMainLooper()
        showKeyboard(chat, bottom = chat.rootView.height - chat.bottomInWindow + 100)
        // 200 of the chat's own pixels cover the keyboard's 100 window pixels.
        assertEquals(200, imeBottom)
    }
}
