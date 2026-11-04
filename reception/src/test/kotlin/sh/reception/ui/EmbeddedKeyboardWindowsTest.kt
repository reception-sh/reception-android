package sh.reception.ui

import android.view.Window
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** A chat in a dialog adjusts the dialog's window, never the activity's, and each window counts its own chats. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class EmbeddedKeyboardWindowsTest {
    private val Window.adjust: Int
        get() = attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST

    @Test
    fun anActivityChatAndADialogChatEachAdjustTheirOwnWindow() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        var activityChat by mutableStateOf(true)
        var dialogChat by mutableStateOf(false)
        var dialogWindow: Window? = null
        activity.setContent {
            if (activityChat) EmbeddedKeyboardAdjustment()
            if (dialogChat) {
                Dialog(onDismissRequest = {}) {
                    val window = (LocalView.current.parent as DialogWindowProvider).window
                    // A host dialog whose window pans, set before the chat composes.
                    DisposableEffect(window) {
                        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
                        dialogWindow = window
                        onDispose {}
                    }
                    EmbeddedKeyboardAdjustment()
                }
            }
        }
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.window.adjust)

        dialogChat = true
        ShadowLooper.idleMainLooper()
        val dialog = checkNotNull(dialogWindow)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, dialog.adjust)

        // The activity's chat leaving gives the activity its mode back; the dialog's chat keeps its own window resized.
        activityChat = false
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, activity.window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, dialog.adjust)

        activityChat = true
        dialogChat = false
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, dialog.adjust)
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
}
