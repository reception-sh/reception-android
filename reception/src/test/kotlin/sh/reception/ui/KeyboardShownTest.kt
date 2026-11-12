package sh.reception.ui

import android.content.Context
import android.view.View
import android.view.WindowInsets
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.reception.testing.MainLooperDrain

/**
 * Gestures ask whether the keyboard is shown, not whether it takes room: a floating keyboard is shown with no inset,
 * and a drag over the messages must still put it away instead of closing the sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardShownTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val context: Context = RuntimeEnvironment.getApplication()

    /** A view whose window reports the keyboard [visible] with a bottom inset of [bottom] pixels. */
    private fun viewInWindow(visible: Boolean, bottom: Int): View = object : View(context) {
        override fun getRootWindowInsets(): WindowInsets = checkNotNull(
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsetsCompat.Type.ime(), visible)
                .build()
                .toWindowInsets(),
        )
    }

    @Test
    fun aFloatingKeyboardIsShownAlthoughItHasNoInset() {
        val view = viewInWindow(visible = true, bottom = 0)
        assertTrue(view.keyboardShown())
        // While it is shown, a drag over the messages puts it away and may not close the sheet.
        assertTrue(SheetGestures.locksSheet(view.keyboardShown(), onHeader = false))
    }

    @Test
    fun aDockedKeyboardIsShown() {
        assertTrue(viewInWindow(visible = true, bottom = 900).keyboardShown())
    }

    @Test
    fun aHiddenKeyboardOrAViewWithoutAWindowIsNotShown() {
        assertFalse(viewInWindow(visible = false, bottom = 0).keyboardShown())
        assertFalse(View(context).keyboardShown())
    }
}
