package sh.reception.ui

import android.app.Activity
import android.view.Window
import android.view.WindowManager.LayoutParams
import android.widget.ScrollView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities

/** A window that would pan for the keyboard resizes while a chat is embedded in it, and gets its mode back after. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class EmbeddedKeyboardModeTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    @After
    fun tearDown() = TestActivities.closeAll()

    private fun window(mode: Int) = TestActivities.build(Activity::class.java).setup().get().window.apply { setSoftInputMode(mode) }

    private val Window.keyboard: KeyboardWindow
        get() = checkNotNull(KeyboardWindow.of(decorView))

    private val Window.adjust: Int
        get() = attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST

    @Test
    fun aPanningWindowResizesUntilTheLastEmbeddedChatLeaves() {
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED or LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_STATE_HIDDEN, window.attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_STATE)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN or LayoutParams.SOFT_INPUT_STATE_HIDDEN, window.attributes.softInputMode)
    }

    @Test
    fun anUnspecifiedModeComesBackAsTheModeAndroidWouldChoose() {
        // No scroll container on screen: Android pans.
        val window = TestActivities.build(Activity::class.java).setup().get().window
        assertEquals(0, window.attributes.softInputMode)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, window.adjust)
        // A shown ScrollView makes Android resize.
        val activity = TestActivities.build(Activity::class.java).setup().get()
        activity.setContentView(ScrollView(activity))
        EmbeddedKeyboardMode.acquire(activity.window.keyboard)
        EmbeddedKeyboardMode.release(activity.window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.window.adjust)
    }

    @Test
    fun adjustPanResizesToo() {
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, window.adjust)
    }

    @Test
    @Config(sdk = [29])
    fun aWindowThatAdjustsNothingResizesBeforeAndroid11() {
        // Android 10 and older send no IME insets to such a window: the composer would stay behind the keyboard.
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_NOTHING or LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE or LayoutParams.SOFT_INPUT_STATE_HIDDEN, window.attributes.softInputMode)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING or LayoutParams.SOFT_INPUT_STATE_HIDDEN, window.attributes.softInputMode)
    }

    @Test
    @Config(sdk = [30])
    fun aWindowThatAdjustsNothingKeepsItsModeFromAndroid11() {
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, window.adjust)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, window.adjust)
    }

    @Test
    fun anAdjustModeTheHostSetWhileTheChatWasShownStays() {
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, window.attributes.softInputMode)
    }

    @Test
    fun stateFlagsTheHostSetWhileTheChatWasShownStay() {
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_PAN or LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_RESIZE or LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN or LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE, window.attributes.softInputMode)
    }
}
