package sh.reception.client

import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.ReceptionState
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities

/** Each test starts as a new process does: no activity known from an earlier test. */
@RunWith(RobolectricTestRunner::class)
class ApplicationLifecycleTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    @After
    fun tearDown() = TestActivities.closeAll()

    @Test
    fun aResetForTheNextTestForgetsTheActivities() {
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        ApplicationLifecycle.noteCurrentActivity(activity)
        assertEquals(listOf(activity), ApplicationLifecycle.aliveActivities())
        ReceptionState.resetForTests()
        assertNull(ApplicationLifecycle.currentActivity)
        assertEquals(emptyList<Any>(), ApplicationLifecycle.aliveActivities())
    }
}
