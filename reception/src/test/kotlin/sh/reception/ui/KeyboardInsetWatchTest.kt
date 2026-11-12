package sh.reception.ui

import android.content.Context
import android.graphics.Insets
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The real watch in a Compose `Dialog` on Android 14 whose decor swallows the keyboard's inset: the window is told once
 * per keyboard show, a quick hide and show included, and never twice in one show, whichever composer has focus (A24-1).
 * Each window write of the soft-input mode is counted; a report to a dialog that already resizes writes the same mode.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardInsetWatchTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    @After
    fun tearDown() = TestActivities.closeAll()

    /** A chat's view in the dialog: the keyboard covers the window's bottom 809 px while [keyboardShown]. */
    private class ChatView(context: Context, private val screen: Screen) : View(context) {
        override fun getRootWindowInsets(): WindowInsets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, if (screen.keyboardShown) 809 else 0))
            .setVisible(WindowInsets.Type.ime(), screen.keyboardShown)
            .build()
    }

    private class Screen {
        var keyboardShown = false
        var dialogShown by mutableStateOf(true)
        var window: Window? = null
        val chats = mutableListOf<ChatView>()
        /** The adjust mode of each write to the dialog's window since it showed. */
        val writes = mutableListOf<Int>()

        init {
            val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
            activity.setContent {
                if (dialogShown) {
                    Dialog(onDismissRequest = {}) {
                        window = (LocalView.current.parent as DialogWindowProvider).window
                        EmbeddedKeyboardAdjustment()
                        Column {
                            repeat(2) { AndroidView(factory = { ChatView(it, this@Screen).also(chats::add) }) }
                        }
                    }
                }
            }
            ShadowLooper.idleMainLooper()
            val dialog = checkNotNull(window)
            val callback = dialog.callback
            dialog.callback = object : Window.Callback by callback {
                override fun onWindowAttributesChanged(attributes: LayoutParams) {
                    writes += attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST
                    callback.onWindowAttributesChanged(attributes)
                }
            }
        }

        /** The watch of chat [index], composing in the dialog; the composition never received the keyboard's inset. */
        fun watch(index: Int): KeyboardInsetWatch =
            KeyboardInsetWatch(chats[index], mutableStateOf(ChatPlacement.Window)) { 0 }.also { it.start() }

        val adjust: Int
            get() = checkNotNull(window).attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST

        /** The keyboard is [shown] or hidden and the dialog lays out for it, then [millis] pass. */
        fun keyboard(shown: Boolean, millis: Long, vararg watches: KeyboardInsetWatch) {
            keyboardShown = shown
            watches.forEach { it.onGlobalLayout() }
            ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun aQuickHideAndShowOfTheKeyboardIsAShowOfItsOwn() {
        val screen = Screen()
        val watch = screen.watch(0)
        watch.composerFocused(true)
        screen.keyboard(shown = true, millis = 300, watch)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.adjust)
        val afterFirst = screen.writes.size

        // Hidden and shown again within the settle time, the composer focused throughout: Android may miss this resize.
        screen.keyboard(shown = false, millis = 100, watch)
        screen.keyboard(shown = true, millis = 300, watch)
        assertEquals("the second show is told too", afterFirst + 1, screen.writes.size)

        // Layouts within the same show tell nothing more.
        screen.keyboard(shown = true, millis = 300, watch)
        assertEquals(afterFirst + 1, screen.writes.size)
        watch.stop()
    }

    @Test
    fun focusMovingBetweenTwoComposersTellsTheWindowOncePerShow() {
        val screen = Screen()
        val first = screen.watch(0)
        val second = screen.watch(1)
        first.composerFocused(true)
        screen.keyboard(shown = true, millis = 300, first, second)
        val afterFirst = screen.writes.size

        // The keyboard stays up while focus goes back and forth.
        repeat(3) {
            first.composerFocused(false)
            second.composerFocused(true)
            screen.keyboard(shown = true, millis = 300, first, second)
            second.composerFocused(false)
            first.composerFocused(true)
            screen.keyboard(shown = true, millis = 300, first, second)
        }
        assertEquals(afterFirst, screen.writes.size)
        first.stop()
        second.stop()
    }

    @Test
    fun aDialogDismissedWhileTheCheckWaitsIsNotWritten() {
        val screen = Screen()
        val watch = screen.watch(0)
        watch.composerFocused(true)
        screen.keyboard(shown = true, millis = 100, watch)
        screen.dialogShown = false
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertFalse("never switched to resize", LayoutParams.SOFT_INPUT_ADJUST_RESIZE in screen.writes)
        watch.stop()
    }

    @Test
    fun anUnfocusedChatLayingOutAMomentLaterDoesNotLoseTheCheck() {
        // Two chats in one dialog see the same layout one millisecond apart; only the first one's composer has focus.
        val screen = Screen()
        val first = screen.watch(0)
        val second = screen.watch(1)
        first.composerFocused(true)
        screen.keyboardShown = true
        first.onGlobalLayout()
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        second.onGlobalLayout()
        val before = screen.writes.size
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        assertEquals("the focused chat's check still tells the window", before + 1, screen.writes.size)
        first.stop()
        second.stop()
    }
}
