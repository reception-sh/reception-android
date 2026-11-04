package sh.reception.ui

import android.app.Activity
import android.view.WindowManager.LayoutParams
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

    private val android.view.Window.adjust: Int
        get() = attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST

    @Test
    fun aPanningWindowResizesUntilTheLastEmbeddedChatLeaves() {
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED or LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        EmbeddedKeyboardMode.acquire(window)
        EmbeddedKeyboardMode.acquire(window)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        assertEquals(LayoutParams.SOFT_INPUT_STATE_HIDDEN, window.attributes.softInputMode and LayoutParams.SOFT_INPUT_MASK_STATE)
        EmbeddedKeyboardMode.release(window)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        EmbeddedKeyboardMode.release(window)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED or LayoutParams.SOFT_INPUT_STATE_HIDDEN, window.attributes.softInputMode)
    }

    @Test
    fun adjustPanResizesToo() {
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_PAN)
        EmbeddedKeyboardMode.acquire(window)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.adjust)
        EmbeddedKeyboardMode.release(window)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_PAN, window.adjust)
    }

    @Test
    fun aWindowThatDoesNotPanKeepsItsMode() {
        val window = window(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        EmbeddedKeyboardMode.acquire(window)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, window.adjust)
        EmbeddedKeyboardMode.release(window)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_NOTHING, window.adjust)
    }
}
