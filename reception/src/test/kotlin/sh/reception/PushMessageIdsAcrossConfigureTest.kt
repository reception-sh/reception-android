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

/** A message delivered once before configure and once after (or the other way round) is handled once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushMessageIdsAcrossConfigureTest {
    private val harness = ChatHarness()
    private val application = RuntimeEnvironment.getApplication()
    private val notifications = application.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val launcher = ComponentName(application.packageName, "Launcher")
        val packages = shadowOf(application.packageManager)
        packages.addActivityIfNotPresent(launcher)
        packages.addIntentFilterForActivity(launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        harness.respond = { request ->
            if (request.path == "/v1/conversation/unread") harness.json("""{"hasConversation":true,"unreadCount":1}""") else null
        }
    }

    @After
    fun tearDown() = harness.stop()

    private fun deliver(messageId: String) {
        val data = mapOf("reception" to "c1", "title" to "Team", "body" to "Hi")
        assertTrue(Reception.handlePushMessage(application, data, messageId))
    }

    /** Lets the storage thread, then the main thread, finish what a delivery started. */
    private fun settle() {
        repeat(3) {
            runBlocking { Storage.run {} }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun posted() = notifications.activeNotifications.size

    @Test
    fun aMessageHandledBeforeConfigureIsNotHandledAgainAfterIt() {
        deliver("0:1700000000000011%abc")
        harness.mainLoopUntil { posted() == 1 }
        notifications.cancelAll()
        harness.start()
        deliver("0:1700000000000011%abc")
        settle()
        assertEquals(0, posted())
    }

    @Test
    fun aMessageHandledAfterConfigureIsNotHandledAgainBeforeTheNextConfigure() {
        harness.start()
        deliver("0:1700000000000012%abc")
        harness.mainLoopUntil { posted() == 1 }
        notifications.cancelAll()
        // A new process that FCM starts for the repeat, before anything configures the SDK.
        ReceptionState.resetForTests()
        deliver("0:1700000000000012%abc")
        settle()
        assertEquals(0, posted())
    }
}
