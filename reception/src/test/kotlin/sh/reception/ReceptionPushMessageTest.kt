package sh.reception

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.reception.client.Storage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sh.reception.viewmodel.ChatHarness

/** An FCM message delivered twice shows one notification and refreshes the unread count once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceptionPushMessageTest {
    private val harness = ChatHarness()
    private val application = RuntimeEnvironment.getApplication()

    /** While set, unread answers wait for it, so a refresh that started stays in flight and visible. */
    @Volatile
    private var gate: CountDownLatch? = null

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
            if (request.path == "/v1/conversation/unread") {
                gate?.await(5, TimeUnit.SECONDS)
                harness.json("""{"hasConversation":true,"unreadCount":1}""")
            } else {
                null
            }
        }
        harness.start()
    }

    @After
    fun tearDown() = harness.stop()

    private val data = mapOf("reception" to "c1", "title" to "Team", "body" to "Hi")

    /** Raw FCM extras, as the Flutter and React Native receivers pass them: the ID is still in the map. */
    private fun deliverExtras(messageId: String) {
        assertTrue(Reception.handlePushMessage(application, data + ("google.message_id" to messageId)))
    }

    /** Through the SDK's service: Firebase removes `google.*` keys from `RemoteMessage.data`. */
    private fun deliverThroughService(messageId: String) {
        val bundle = Bundle().apply {
            data.forEach { (key, value) -> putString(key, value) }
            putString("google.message_id", messageId)
        }
        val message = RemoteMessage(bundle)
        assertEquals(null, message.data["google.message_id"])
        Robolectric.buildService(ReceptionMessagingService::class.java).create().get().onMessageReceived(message)
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

    private fun awaitRefresh() = harness.mainLoopUntil { ReceptionUnread.runningRefresh == null }

    @Test
    fun aRepeatedMessageIdIsHandledOnce() = assertHandledOnce(::deliverExtras)

    @Test
    fun aRepeatedRemoteMessageThroughTheServiceIsHandledOnce() = assertHandledOnce(::deliverThroughService)

    private fun assertHandledOnce(deliver: (String) -> Unit) {
        // The launch's own unread refresh finishes first, so it cannot absorb the push's refresh.
        harness.mainLoopUntil { unreadRequests() == 1 }
        awaitRefresh()
        val before = unreadRequests()
        deliver("0:1700000000000001%abc")
        harness.mainLoopUntil { posted() == 1 && unreadRequests() == before + 1 }
        awaitRefresh()
        application.getSystemService(NotificationManager::class.java).cancelAll()
        val held = CountDownLatch(1).also { gate = it }
        deliver("0:1700000000000001%abc")
        settle()
        // A refresh the repeat started would still be waiting for its gated answer.
        assertNull(ReceptionUnread.runningRefresh)
        assertEquals(0, posted())
        assertEquals(before + 1, unreadRequests())
        gate = null
        held.countDown()
        deliver("0:1700000000000002%abc")
        harness.mainLoopUntil { posted() == 1 && unreadRequests() == before + 2 }
    }
}
