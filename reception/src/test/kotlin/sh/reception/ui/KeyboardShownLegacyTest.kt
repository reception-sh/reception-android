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
 * Before Android 11 the platform reports the keyboard only through its inset: a docked keyboard is shown, a floating
 * one (no inset) counts as closed for gestures. SPEC.md states this limit; the test keeps it a known one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29])
class KeyboardShownLegacyTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val context: Context = RuntimeEnvironment.getApplication()

    /**
     * A view whose window reports [systemBottom] pixels of system window inset at the bottom, the only form in which
     * these versions deliver a docked keyboard (a resized window), and a keyboard asked to be [visible].
     */
    private fun viewInWindow(visible: Boolean, systemBottom: Int): View = object : View(context) {
        override fun getRootWindowInsets(): WindowInsets = checkNotNull(
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, systemBottom))
                .setVisible(WindowInsetsCompat.Type.ime(), visible)
                .build()
                .toWindowInsets(),
        )
    }

    @Test
    fun aDockedKeyboardIsShown() {
        assertTrue(viewInWindow(visible = true, systemBottom = 900).keyboardShown())
    }

    @Test
    fun aFloatingKeyboardCountsAsClosed() {
        assertFalse(viewInWindow(visible = true, systemBottom = 0).keyboardShown())
    }
}
