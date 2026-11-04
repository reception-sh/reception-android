package sh.reception

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.reception.client.Storage
import sh.reception.viewmodel.ChatHarness

/** An FCM message delivered twice shows one notification and refreshes the unread count once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceptionPushMessageTest {
    private val harness = ChatHarness()
    private val application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val launcher = ComponentName(application.packageName, "Launcher")
        val packages = shadowOf(application.packageManager)
        packages.addActivityIfNotPresent(launcher)
        packages.addIntentFilterForActivity(launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        // At zero unread the SDK removes its notifications, so the reply stays unread.
        harness.respond = { request ->
            if (request.path == "/v1/conversation/unread") harness.json("""{"hasConversation":true,"unreadCount":1}""") else null
        }
        harness.start()
    }

    @After
    fun tearDown() = harness.stop()

    private fun deliver(messageId: String) {
        val data = mapOf("reception" to "c1", "title" to "Team", "body" to "Hi", "google.message_id" to messageId)
        assertTrue(Reception.handlePushMessage(application, data))
    }

    /** Lets the storage thread, then the main thread, finish what a delivery started. */
    private fun settle() {
        repeat(3) {
            runBlocking { Storage.run {} }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun posted() = application.getSystemService(NotificationManager::class.java).activeNotifications.size

    private fun unreadRequests() = harness.requests("GET", "/v1/conversation/unread").size

    @Test
    fun aRepeatedMessageIdIsHandledOnce() {
        // The launch's own unread refresh finishes first, so it cannot absorb the push's refresh.
        harness.mainLoopUntil { unreadRequests() == 1 }
        Thread.sleep(200)
        settle()
        val before = unreadRequests()
        deliver("0:1700000000000001%abc")
        harness.mainLoopUntil { posted() == 1 }
        harness.mainLoopUntil { unreadRequests() == before + 1 }
        application.getSystemService(NotificationManager::class.java).cancelAll()
        deliver("0:1700000000000001%abc")
        settle()
        Thread.sleep(200)
        settle()
        assertEquals(0, posted())
        assertEquals(before + 1, unreadRequests())
        deliver("0:1700000000000002%abc")
        harness.mainLoopUntil { posted() == 1 && unreadRequests() == before + 2 }
    }
}
