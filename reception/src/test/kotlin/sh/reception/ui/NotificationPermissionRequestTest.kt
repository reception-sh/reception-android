package sh.reception.ui

import android.Manifest
import android.app.NotificationManager
import android.content.MutableContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.reception.client.NotificationAuthorization
import sh.reception.viewmodel.ChatHarness

/** The notification card reads the permission through the activity the chat is in now, not the one it started in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPermissionRequestTest {
    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    /** An activity where the system has asked before: the rationale flag is on. */
    class AskedActivity : ComponentActivity() {
        override fun shouldShowRequestPermissionRationale(permission: String): Boolean = true
    }

    @Test
    fun aReboundContextWrapperIsReadThroughItsCurrentActivity() {
        val model = harness.start()
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(application.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val wrapper = MutableContextWrapper(first.get())
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        host.get().setContent {
            CompositionLocalProvider(LocalContext provides wrapper) { rememberNotificationPermission(model) }
        }
        harness.mainLoopUntil { model.notificationAuthorization != null }
        assertEquals(NotificationAuthorization.NOT_DETERMINED, model.notificationAuthorization)
        // The host moves the view to a new activity; the first one is gone.
        val second = Robolectric.buildActivity(AskedActivity::class.java).setup()
        wrapper.setBaseContext(second.get())
        first.pause().stop().destroy()
        host.pause().resume()
        harness.mainLoopUntil { model.notificationAuthorization == NotificationAuthorization.DENIED }
    }
}
