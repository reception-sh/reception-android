package sh.reception.ui

import android.view.Window
import android.widget.FrameLayout
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A chat in a dialog adjusts the dialog's window, never the activity's, and each window counts its own chats. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class EmbeddedKeyboardWindowsTest {
    private val Window.adjust: Int
        get() = attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST

    /** An activity chat and a Compose `Dialog` chat whose window the host set to [dialogMode]. */
    private class Screen(dialogMode: Int) {
        val activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var activityChat by mutableStateOf(true)
        var dialogChat by mutableStateOf(false)
        var dialogWindow: Window? = null

        init {
            activity.window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
            activity.setContent {
                if (activityChat) EmbeddedKeyboardAdjustment()
                if (dialogChat) {
                    Dialog(onDismissRequest = {}) {
                        val window = (LocalView.current.parent as DialogWindowProvider).window
                        // The host's dialog mode, set before the chat composes.
                        DisposableEffect(window) {
                            window.setSoftInputMode(dialogMode)
                            dialogWindow = window
                            onDispose {}
                        }
                        EmbeddedKeyboardAdjustment()
                    }
                }
            }
            ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun anActivityChatAndADialogChatEachAdjustTheirOwnWindow() {
        val screen = Screen(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.activity.window.adjust)

        // A Compose dialog's content never shrinks with its window, so it adjusts nothing and the insets lift the chat.
        screen.dialogChat = true
        ShadowLooper.idleMainLooper()
        val dialog = checkNotNull(screen.dialogWindow)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, dialog.adjust)

        // The activity's chat leaving gives the activity its mode back; the dialog's chat keeps its own window adjusted.
        screen.activityChat = false
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, screen.activity.window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, dialog.adjust)

        screen.activityChat = true
        screen.dialogChat = false
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.activity.window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, dialog.adjust)
    }

    @Test
    @Config(sdk = [29])
    fun aComposeDialogPansBeforeAndroid11() {
        // Before Android 11 only a resize sends IME insets, and a Compose dialog cannot resize its content.
        val screen = Screen(LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        screen.dialogChat = true
        ShadowLooper.idleMainLooper()
        val dialog = checkNotNull(screen.dialogWindow)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, dialog.adjust)
        screen.dialogChat = false
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, dialog.adjust)
    }

    @Test
    fun aChatInAPlatformDialogAdjustsTheDialogsWindowOnly() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        val dialog = ComponentDialog(activity)
        val window = checkNotNull(dialog.window)
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        dialog.setContentView(ComposeView(activity).apply { setContent { EmbeddedKeyboardAdjustment() } })
        dialog.show()
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, activity.window.adjust)

        dialog.dismiss()
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, activity.window.adjust)
    }

    @Test
    @Config(sdk = [29])
    @OptIn(ExperimentalMaterial3Api::class)
    fun aChatInAModalBottomSheetResizesItsWindowBeforeAndroid11() {
        // The sheet lays out across its whole window and resizes with it; only resizing sends IME insets here.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        var sheetWindow: Window? = null
        activity.setContent {
            ModalBottomSheet(onDismissRequest = {}) {
                sheetWindow = (LocalView.current.parent as DialogWindowProvider).window
                EmbeddedKeyboardAdjustment()
            }
        }
        ShadowLooper.idleMainLooper()
        val window = checkNotNull(sheetWindow)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, activity.window.adjust)

    }

    @Test
    @Config(sdk = [29])
    fun anotherDialogWindowProviderFollowsTheResizeRule() {
        // A dialog whose content view provides its window, as a bottom sheet's does, but which pans.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val dialog = ComponentDialog(activity)
        val window = checkNotNull(dialog.window)
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        val content = object : FrameLayout(activity), DialogWindowProvider {
            override val window: Window = window
        }
        content.addView(ComposeView(activity).apply { setContent { EmbeddedKeyboardAdjustment() } })
        dialog.setContentView(content)
        dialog.show()
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        dialog.dismiss()
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, window.adjust)
    }
}
