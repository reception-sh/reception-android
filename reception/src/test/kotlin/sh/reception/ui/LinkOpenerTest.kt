package sh.reception.ui

import android.app.Activity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import sh.reception.client.ApplicationLifecycle
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities

/** Android resolves schemes case-sensitively, so every launch carries the scheme in lowercase. */
@RunWith(RobolectricTestRunner::class)
class LinkOpenerTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    @After
    fun tearDown() = TestActivities.closeAll()

    @Test
    fun aMixedCaseSchemeLaunchesInLowercase() {
        val activity = TestActivities.build(Activity::class.java).setup().get()
        ApplicationLifecycle.noteCurrentActivity(activity)
        assertTrue(LinkOpener.openExternally("MyApp://Orders/7"))
        assertEquals("myapp://Orders/7", shadowOf(activity).nextStartedActivity.dataString)
        assertTrue(LinkOpener.openCustomTab("HTTPS://Example.com/Help", 0))
        assertEquals("https://Example.com/Help", shadowOf(activity).nextStartedActivity.dataString)
        ApplicationLifecycle.onActivityPaused(activity)
    }
}
