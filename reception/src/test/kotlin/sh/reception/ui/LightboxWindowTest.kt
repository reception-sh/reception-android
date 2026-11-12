package sh.reception.ui

import android.app.Dialog
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import sh.reception.model.Attachment
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness
import java.util.concurrent.TimeUnit

/**
 * The lightbox's window covers the whole display, both system bars and the cutout included, whatever Compose version
 * the host runs: its size, inset fitting and bar icons are the SDK's, not left to a Compose `Dialog`, which sizes its
 * window differently per version (1.7: the display metrics centred between the bars; 1.8 and newer: full width, wrap
 * height, fitted inside the bars).
 */
@RunWith(RobolectricTestRunner::class)
class LightboxWindowTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    private val photo = Attachment(id = "a1", url = "https://example.com/a1.jpg", contentType = "image/jpeg",
        width = 400, height = 300)

    private var dismissals = 0
    private val activity = TestActivities.build(ComponentActivity::class.java)

    private fun open(secure: Boolean = false): Dialog {
        harness.start()
        val host = activity.setup().get()
        if (secure) host.window.addFlags(LayoutParams.FLAG_SECURE)
        host.setContent { ChatRoot(embedded = true) { ImageLightbox(photo) { dismissals += 1 } } }
        settle()
        val dialog: Dialog? = ShadowDialog.getLatestDialog()
        assertNotNull("no lightbox window", dialog)
        return requireNotNull(dialog)
    }

    private fun settle() = repeat(3) {
        harness.settle()
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    }

    @Test
    @Config(sdk = [34])
    fun theWindowFillsTheDisplayAndFitsNoInsets() {
        val window = requireNotNull(open().window)
        settle()
        val attributes = window.attributes
        assertEquals("width", ViewGroup.LayoutParams.MATCH_PARENT, attributes.width)
        assertEquals("height", ViewGroup.LayoutParams.MATCH_PARENT, attributes.height)
        assertEquals("fit insets types", 0, attributes.fitInsetsTypes)
        assertEquals("fit insets sides", 0, attributes.fitInsetsSides)
        assertTrue("no limits", attributes.flags and LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        assertEquals(LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, attributes.layoutInDisplayCutoutMode)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertFalse("status bar icons light on black", controller.isAppearanceLightStatusBars)
        assertFalse("navigation bar icons light on black", controller.isAppearanceLightNavigationBars)
    }

    @Test
    @Config(sdk = [29])
    fun beforeAndroid11TheWindowIsLaidOutUnderTheBarsAndStillToldOfThem() {
        val window = requireNotNull(open().window)
        settle()
        val attributes = window.attributes
        assertEquals("width", ViewGroup.LayoutParams.MATCH_PARENT, attributes.width)
        assertEquals("height", ViewGroup.LayoutParams.MATCH_PARENT, attributes.height)
        assertTrue("in screen", attributes.flags and LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        // Android 10 reports no system bar insets to a window without limits: the close button sat in the status bar.
        assertFalse("no limits", attributes.flags and LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        @Suppress("DEPRECATION")
        val underTheBars = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        @Suppress("DEPRECATION")
        assertEquals("laid out under the bars", underTheBars, window.decorView.systemUiVisibility and underTheBars)
        assertEquals(LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, attributes.layoutInDisplayCutoutMode)
    }

    @Test
    @Config(sdk = [34])
    fun aSecureHostKeepsTheLightboxSecure() {
        val window = requireNotNull(open(secure = true).window)
        assertTrue(window.attributes.flags and LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    @Config(sdk = [34])
    fun backAsksTheChatToCloseAndTheWindowClosesWithItsComposition() {
        val dialog = open()
        assertTrue(dialog.isShowing)
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertEquals(1, dismissals)
        // Rotation or a closed chat disposes the composition; the window goes with it and leaks nothing.
        activity.pause().stop().destroy()
        assertFalse(dialog.isShowing)
    }
}
