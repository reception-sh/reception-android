package sh.reception.ui

import android.app.Activity
import android.view.Window
import android.view.WindowManager.LayoutParams
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** A window that would pan for the keyboard resizes while a chat is embedded in it, and gets its mode back after. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class EmbeddedKeyboardModeTest {
    private fun window(mode: Int) = Robolectric.buildActivity(Activity::class.java).setup().get().window.apply { setSoftInputMode(mode) }

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
        val window = Robolectric.buildActivity(Activity::class.java).setup().get().window
        assertEquals(0, window.attributes.softInputMode)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, window.adjust)
        // A shown ScrollView makes Android resize.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
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
    fun aWindowThatDoesNotPanKeepsItsMode() {
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        EmbeddedKeyboardMode.acquire(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, window.adjust)
        EmbeddedKeyboardMode.release(window.keyboard)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, window.adjust)
    }
}
