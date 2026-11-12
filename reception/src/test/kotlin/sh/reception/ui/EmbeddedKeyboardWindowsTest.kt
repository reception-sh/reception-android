package sh.reception.ui

import android.view.View
import android.view.ViewGroup
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
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import sh.reception.Log
import sh.reception.Reception
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities

/** A chat in a dialog adjusts the dialog's window, never the activity's, and each window counts its own chats. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class EmbeddedKeyboardWindowsTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val lines = mutableListOf<String>()

    @After
    fun tearDown() {
        TestActivities.closeAll()
        Log.handler = null
        Log.level = Reception.LogLevel.INFO
    }

    private val Window.adjust: Int
        get() = attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST

    /**
     * An activity chat and a Compose `Dialog` chat whose window the host set to [dialogMode]; [decorFits] is the dialog's
     * `decorFitsSystemWindows`, [defaultWidth] its `usePlatformDefaultWidth`.
     */
    private class Screen(dialogMode: Int, decorFits: Boolean = true, defaultWidth: Boolean = true) {
        val activity: ComponentActivity = TestActivities.build(ComponentActivity::class.java).setup().get()
        var activityChat by mutableStateOf(true)
        var dialogChat by mutableStateOf(false)
        var chatInDialog by mutableStateOf(true)
        var dialogWindow: Window? = null
        var dialogView: View? = null

        init {
            activity.window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
            activity.setContent {
                if (activityChat) EmbeddedKeyboardAdjustment()
                if (dialogChat) {
                    val properties = DialogProperties(decorFitsSystemWindows = decorFits, usePlatformDefaultWidth = defaultWidth)
                    Dialog(onDismissRequest = {}, properties = properties) {
                        val window = (LocalView.current.parent as DialogWindowProvider).window
                        dialogView = LocalView.current
                        // The host's dialog mode, set before the chat composes.
                        DisposableEffect(window) {
                            window.setSoftInputMode(dialogMode)
                            dialogWindow = window
                            onDispose {}
                        }
                        if (chatInDialog) EmbeddedKeyboardAdjustment()
                    }
                }
            }
            ShadowLooper.idleMainLooper()
        }

        val dialog: Window
            get() = checkNotNull(dialogWindow)

        /** What [KeyboardInsetWatch] reports when the dialog swallowed the keyboard's inset above the chat. */
        fun swallowKeyboardInset() {
            EmbeddedKeyboardMode.keyboardInsetSwallowed(checkNotNull(KeyboardWindow.of(checkNotNull(dialogView))))
            ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun anActivityChatAndADialogChatEachAdjustTheirOwnWindow() {
        val screen = Screen(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.activity.window.adjust)

        // From Android 11 a Compose dialog adjusts nothing, so an edge-to-edge one lifts the chat with the insets.
        screen.dialogChat = true
        ShadowLooper.idleMainLooper()
        val dialog = screen.dialog
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

    /** Each of the four dialog configurations a host can choose starts with [adjust], whatever its flags say. */
    private fun assertEveryConfigurationStartsWith(adjust: Int) {
        for (decorFits in listOf(true, false)) {
            for (defaultWidth in listOf(true, false)) {
                val screen = Screen(LayoutParams.SOFT_INPUT_ADJUST_PAN, decorFits, defaultWidth)
                screen.dialogChat = true
                ShadowLooper.idleMainLooper()
                assertEquals("decorFits=$decorFits defaultWidth=$defaultWidth", adjust, screen.dialog.adjust)
                screen.dialogChat = false
                ShadowLooper.idleMainLooper()
            }
        }
    }

    @Test
    @Config(sdk = [34])
    fun everyComposeDialogStartsAdjustingNothingFromAndroid11() = assertEveryConfigurationStartsWith(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

    @Test
    @Config(sdk = [30])
    fun everyComposeDialogStartsAdjustingNothingOnAndroid11() = assertEveryConfigurationStartsWith(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

    @Test
    @Config(sdk = [34])
    fun aComposeDialogThatSwallowedTheKeyboardsInsetResizesForAsLongAsItsWindowExists() {
        // Its decor fits system windows and consumed the IME inset before the chat (Android 14 live: root 809 px,
        // composition 0); its content follows the window.
        val screen = Screen(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        screen.dialogChat = true
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, screen.dialog.adjust)
        screen.swallowKeyboardInset()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.dialog.adjust)

        // The chat leaving gives the host its mode back; a chat in the same window again resizes without waiting.
        screen.chatInDialog = false
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, screen.dialog.adjust)
        screen.chatInDialog = true
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.dialog.adjust)

        // A new dialog is a new window: it starts adjusting nothing again.
        screen.dialogChat = false
        ShadowLooper.idleMainLooper()
        screen.dialogChat = true
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, screen.dialog.adjust)
    }

    @Test
    @Config(sdk = [34])
    fun aSwallowingDialogWhoseHostChoseItsOwnModeKeepsIt() {
        val screen = Screen(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        screen.dialogChat = true
        ShadowLooper.idleMainLooper()
        screen.swallowKeyboardInset()
        screen.dialog.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        // A later swallowed show asks the window to lay out again only while it still has the chat's resize.
        screen.swallowKeyboardInset()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, screen.dialog.adjust)
    }

    /** The host sets [hostMode] on the dialog while the chat is in it; the dialog swallows the inset twice, the chat leaves. */
    private fun assertTheHostsModeStays(dialogMode: Int, hostMode: Int) {
        Log.level = Reception.LogLevel.DEBUG
        Log.handler = { _, line -> lines += line }
        val screen = Screen(dialogMode)
        screen.dialogChat = true
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, screen.dialog.adjust)
        screen.dialog.setSoftInputMode(hostMode)
        screen.swallowKeyboardInset()
        assertEquals("the host's mode stays", hostMode, screen.dialog.adjust)
        screen.swallowKeyboardInset()
        assertEquals(hostMode, screen.dialog.adjust)
        screen.chatInDialog = false
        ShadowLooper.idleMainLooper()
        assertEquals("nothing from before the host's choice comes back", hostMode, screen.dialog.adjust)
        assertEquals("said once", 1, lines.count { it.startsWith("The app set its own keyboard mode") })
    }

    @Test
    @Config(sdk = [34])
    fun aModeTheHostSetBeforeTheFirstSwallowedShowStays() =
        // A24-2: the SDK wrote `adjustNothing` over the host's resize; the host then chose pan.
        assertTheHostsModeStays(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, hostMode = LayoutParams.SOFT_INPUT_ADJUST_PAN)

    @Test
    @Config(sdk = [34])
    fun aModeTheHostSetOnADialogTheSdkNeverWroteStays() =
        // The dialog already adjusted nothing, so acquiring wrote nothing; the SDK still expects that mode.
        assertTheHostsModeStays(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, hostMode = LayoutParams.SOFT_INPUT_ADJUST_PAN)

    @Test
    @Config(sdk = [34])
    fun aFittingDialogWhoseHostMarkedItsDecorLayoutStableIsHandledLikeAnyFittingDialog() {
        // Round 22 took `SYSTEM_UI_FLAG_LAYOUT_STABLE` for an edge-to-edge decor and never resized this dialog (A22-1).
        val screen = Screen(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        screen.chatInDialog = false
        screen.dialogChat = true
        ShadowLooper.idleMainLooper()
        screen.dialog.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        screen.chatInDialog = true
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, screen.dialog.adjust)
        screen.swallowKeyboardInset()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, screen.dialog.adjust)
    }

    @Test
    @Config(sdk = [35])
    fun anEdgeToEdgeDialogOfAnAppTargeting34OnAndroid15IsNeverResized() {
        // No enforcement and no `LAYOUT_STABLE` mark here (androidx.core 1.15 on API 35 sets neither): round 22 resized
        // this dialog, whose content gets the IME inset and is measured against the display (A22-1).
        val application = RuntimeEnvironment.getApplication()
        val target = application.applicationInfo.targetSdkVersion
        application.applicationInfo.targetSdkVersion = 34
        try {
            val screen = Screen(LayoutParams.SOFT_INPUT_ADJUST_PAN, decorFits = false)
            screen.dialogChat = true
            ShadowLooper.idleMainLooper()
            assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, screen.dialog.adjust)
        } finally {
            application.applicationInfo.targetSdkVersion = target
        }
    }

    @Test
    @Config(sdk = [29])
    fun aComposeDialogPansBeforeAndroid11() {
        // Before Android 11 only a resize sends IME insets, and a Compose dialog cannot resize its content.
        val screen = Screen(LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        screen.dialogChat = true
        ShadowLooper.idleMainLooper()
        val dialog = screen.dialog
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, dialog.adjust)
        // There is no keyboard inset to swallow before Android 11: a report changes nothing.
        screen.swallowKeyboardInset()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, dialog.adjust)
        screen.dialogChat = false
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, dialog.adjust)
    }

    @Test
    fun aChatInAPlatformDialogAdjustsTheDialogsWindowOnly() {
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        activity.window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        val dialog = ComponentDialog(activity)
        val window = checkNotNull(dialog.window)
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        dialog.setContentView(ComposeView(activity).apply { setContent { EmbeddedKeyboardAdjustment() } })
        TestActivities.show(dialog)
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
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
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
        // A dialog whose content view provides its window, as a host's Material bottom sheet does, but which pans.
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        val dialog = ComponentDialog(activity)
        val window = checkNotNull(dialog.window)
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        val content = object : FrameLayout(activity), DialogWindowProvider {
            override val window: Window = window
        }
        content.addView(ComposeView(activity).apply { setContent { EmbeddedKeyboardAdjustment() } })
        dialog.setContentView(content)
        TestActivities.show(dialog)
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        dialog.dismiss()
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, window.adjust)
    }

    @Test
    fun aRetainedChatMovedToAnotherWindowAdjustsTheNewWindowAndReleasesTheOld() {
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        activity.window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        // The composition outlives detaching: it ends only with the activity.
        val chat = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(activity))
            setContent { EmbeddedKeyboardAdjustment() }
        }
        activity.setContentView(chat)
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.window.adjust)

        val dialog = ComponentDialog(activity)
        val window = checkNotNull(dialog.window)
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        activity.setContentView(FrameLayout(activity))
        dialog.setContentView(chat)
        TestActivities.show(dialog)
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, activity.window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)

        dialog.dismiss()
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, window.adjust)
        (chat.parent as ViewGroup).removeView(chat)
        activity.setContentView(chat)
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.window.adjust)

        // The chat leaving the composition releases the window once; detaching afterwards releases nothing again.
        val otherChat = ComposeView(activity).apply { setContent { EmbeddedKeyboardAdjustment() } }
        activity.addContentView(otherChat, ViewGroup.LayoutParams(0, 0))
        chat.setContent {}
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.window.adjust)
        (chat.parent as ViewGroup).removeView(chat)
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.window.adjust)
        (otherChat.parent as ViewGroup).removeView(otherChat)
        ShadowLooper.idleMainLooper()
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, activity.window.adjust)
    }
}
