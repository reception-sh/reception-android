package sh.reception.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager.LayoutParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities

/**
 * The sheet's window never shows the keyboard by itself: before Android 9 the platform shows it for a window that
 * resizes for it and has a focusable text field when the user navigates forward to it, unless the state says hidden.
 * A recreated sheet (rotation, restore) and a covered sheet brought back to the front leave the keyboard as it was:
 * Android treats both as a forward navigation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class ChatActivityKeyboardTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    @After
    fun tearDown() = TestActivities.closeAll()

    @Test
    fun theSheetsWindowHidesTheKeyboardWhenItOpensAndResizesForIt() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getActivityInfo(ComponentName(context, ReceptionChatActivity::class.java), 0)
        assertEquals(LayoutParams.SOFT_INPUT_STATE_HIDDEN, info.softInputMode and LayoutParams.SOFT_INPUT_MASK_STATE)
        @Suppress("DEPRECATION")
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, info.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }

    @Test
    fun aNewSheetHidesTheKeyboard() {
        val mode = sheet(savedState = null).window.attributes.softInputMode
        assertEquals(LayoutParams.SOFT_INPUT_STATE_HIDDEN, mode and LayoutParams.SOFT_INPUT_MASK_STATE)
    }

    @Test
    fun aRecreatedSheetLeavesTheKeyboardAsItWas() {
        val mode = sheet(savedState = Bundle()).window.attributes.softInputMode
        assertEquals(LayoutParams.SOFT_INPUT_STATE_UNCHANGED, mode and LayoutParams.SOFT_INPUT_MASK_STATE)
        @Suppress("DEPRECATION")
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }

    @Test
    fun aCoveredSheetBroughtToTheFrontLeavesTheKeyboardAsItIs() {
        val covered = sheet(savedState = null)
        val host = TestActivities.build(Activity::class.java).create().get()
        assertTrue(CoveredSheet.bringToFront(host, covered))
        val mode = covered.window.attributes.softInputMode
        assertEquals(LayoutParams.SOFT_INPUT_STATE_UNCHANGED, mode and LayoutParams.SOFT_INPUT_MASK_STATE)
        @Suppress("DEPRECATION")
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, mode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
        val reorder = shadowOf(host).nextStartedActivity
        assertEquals(ReceptionChatActivity::class.java.name, reorder.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, reorder.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }

    /** A sheet without a presentation, which finishes at once; its window keeps the mode it was created with. */
    private fun sheet(savedState: Bundle?): ReceptionChatActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), ReceptionChatActivity::class.java)
        return TestActivities.build(ReceptionChatActivity::class.java, intent).create(savedState).get()
    }
}
