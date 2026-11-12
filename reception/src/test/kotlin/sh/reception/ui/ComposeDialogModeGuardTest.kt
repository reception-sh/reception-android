package sh.reception.ui

import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities

/** Compose rewrites a `Dialog`'s soft-input mode on each of its recompositions below Android 12; the chat's comes back. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class ComposeDialogModeGuardTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    @After
    fun tearDown() = TestActivities.closeAll()

    private class Screen(decorFitsSystemWindows: Boolean, chat: Boolean = true) {
        var dismissOnBackPress by mutableStateOf(true)
        var chat by mutableStateOf(chat)
        var window: Window? = null
        var view: View? = null

        init {
            val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
            activity.setContent {
                val properties = DialogProperties(dismissOnBackPress = dismissOnBackPress, decorFitsSystemWindows = decorFitsSystemWindows)
                Dialog(onDismissRequest = {}, properties = properties) {
                    view = LocalView.current
                    window = (LocalView.current.parent as DialogWindowProvider).window
                    if (this.chat) EmbeddedKeyboardAdjustment()
                }
            }
            ShadowLooper.idleMainLooper()
        }

        val mode: Int
            get() = checkNotNull(window).attributes.softInputMode

        /** A parameter change: the `Dialog` call recomposes and Compose updates its window. */
        fun changeParameters() {
            dismissOnBackPress = !dismissOnBackPress
            ShadowLooper.idleMainLooper()
        }
    }

    /** [fitting] is the chat's mode in a dialog whose decor fits system windows, [edgeToEdge] in one that does not. */
    private fun assertKept(fitting: Int, edgeToEdge: Int) {
        for (decorFitsSystemWindows in listOf(true, false)) {
            val adjust = if (decorFitsSystemWindows) fitting else edgeToEdge
            val screen = Screen(decorFitsSystemWindows)
            assertEquals(adjust, screen.mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
            screen.changeParameters()
            assertEquals("decorFitsSystemWindows=$decorFitsSystemWindows", adjust, screen.mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
            screen.changeParameters()
            assertEquals(adjust, screen.mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
            // Without a chat, Compose's own mode stays: the dialog theme's `adjustPan`, or `adjustResize`.
            screen.chat = false
            ShadowLooper.idleMainLooper()
            screen.changeParameters()
            val composeMode = if (decorFitsSystemWindows) LayoutParams.SOFT_INPUT_ADJUST_PAN else LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            assertEquals(composeMode, screen.mode)
        }
    }

    @Test
    @Config(sdk = [29])
    fun aDialogParameterChangeKeepsThePanOnAndroid10() =
        assertKept(fitting = LayoutParams.SOFT_INPUT_ADJUST_PAN, edgeToEdge = LayoutParams.SOFT_INPUT_ADJUST_PAN)

    @Test
    @Config(sdk = [30])
    fun aDialogParameterChangeKeepsTheChatsModeOnAndroid11() =
        assertKept(fitting = LayoutParams.SOFT_INPUT_ADJUST_NOTHING, edgeToEdge = LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

    @Test
    @Config(sdk = [30])
    fun aDialogThatSwallowedTheKeyboardsInsetKeepsItsResizeThroughAParameterChange() {
        val screen = Screen(decorFitsSystemWindows = true)
        EmbeddedKeyboardMode.keyboardInsetSwallowed(checkNotNull(KeyboardWindow.of(checkNotNull(screen.view))))
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
        // Compose writes the dialog theme's `adjustPan` back; the chat's resize returns.
        screen.changeParameters()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
        screen.chat = false
        ShadowLooper.idleMainLooper()
        screen.changeParameters()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, screen.mode)
    }

    @Test
    @Config(sdk = [30])
    fun aModeTheHostChoseWhileTheChatIsShownStays() {
        val screen = Screen(decorFitsSystemWindows = true)
        val hostMode = LayoutParams.SOFT_INPUT_ADJUST_PAN or LayoutParams.SOFT_INPUT_STATE_HIDDEN
        checkNotNull(screen.window).setSoftInputMode(hostMode)
        ShadowLooper.idleMainLooper()
        assertEquals(hostMode, screen.mode)
    }

    @Test
    @Config(sdk = [30])
    fun aWindowThatAlreadyAdjustsNothingKeepsItThroughAParameterChange() {
        val screen = Screen(decorFitsSystemWindows = false, chat = false)
        // The window already has the chat's mode when the chat appears, so the SDK writes nothing.
        checkNotNull(screen.window).setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        screen.chat = true
        ShadowLooper.idleMainLooper()
        screen.changeParameters()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, screen.mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
        // Without a chat, Compose's own mode stays, and the mode the window had is not written back over it.
        screen.chat = false
        ShadowLooper.idleMainLooper()
        screen.changeParameters()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.mode)
    }

    @Test
    @Config(sdk = [30])
    fun aHostModeSeenBeforeAFrameEndsTheChatsAdjustingForGood() {
        val screen = Screen(decorFitsSystemWindows = true)
        val window = checkNotNull(screen.window)
        val hostMode = LayoutParams.SOFT_INPUT_ADJUST_PAN or LayoutParams.SOFT_INPUT_STATE_HIDDEN
        window.setSoftInputMode(hostMode)
        window.decorView.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(hostMode, screen.mode)
        // Compose later writes its theme's mode, exactly as its `updateParameters` does for this fitting dialog.
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        window.decorView.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, screen.mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
        // Nothing is restored over the window when the chat leaves.
        screen.chat = false
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, screen.mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }
}
