package sh.reception

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sh.reception.client.Storage
import sh.reception.viewmodel.ChatHarness

/** The app's notification icon reaches a process that FCM starts before any of the app's code set it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSmallIconTest {
    private val harness = ChatHarness()
    private val application = RuntimeEnvironment.getApplication()
    private val notifications = application.getSystemService(NotificationManager::class.java)
    private val preferences = application.getSharedPreferences("sh.reception.push", Context.MODE_PRIVATE)
    private var nextId = 0

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

    /** A message before configure, as in a process FCM started; returns the posted notification's small icon. */
    private fun deliver(): Int {
        notifications.cancelAll()
        val data = mapOf("reception" to "c1", "title" to "Team", "body" to "Hi")
        assertTrue(Reception.handlePushMessage(application, data, "0:17000000000000${nextId++}%icon"))
        harness.mainLoopUntil { notifications.activeNotifications.size == 1 }
        return notifications.activeNotifications.single().notification.smallIcon.resId
    }

    /** Lets the storage thread, then the main thread, finish what was started. */
    private fun settle() {
        repeat(3) {
            runBlocking { Storage.run {} }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** A new process: nothing in memory, the stored values stay. */
    private fun restartProcess() = ReceptionState.resetForTests()

    @Test
    fun anIconSetInAnEarlierProcessIsUsedBeforeTheAppSetsItAgain() {
        Reception.notificationSmallIcon = R.drawable.reception_symbol_chat
        assertEquals(R.drawable.reception_symbol_chat, deliver())
        settle()
        assertEquals(application.resources.getResourceName(R.drawable.reception_symbol_chat), preferences.getString("notificationSmallIcon", null))

        restartProcess()
        assertNull(Reception.notificationSmallIcon)
        assertEquals(R.drawable.reception_symbol_chat, deliver())
        assertEquals(R.drawable.reception_symbol_chat, Reception.notificationSmallIcon)

        // Null clears the stored icon too.
        Reception.notificationSmallIcon = null
        settle()
        assertNull(preferences.getString("notificationSmallIcon", null))
        restartProcess()
        assertEquals(R.drawable.reception_symbol_notifications, deliver())
    }

    @Test
    fun anIconSetBeforeAnyContextIsStoredAtConfigure() {
        Reception.notificationSmallIcon = R.drawable.reception_symbol_forum
        harness.start()
        settle()
        assertEquals(application.resources.getResourceName(R.drawable.reception_symbol_forum), preferences.getString("notificationSmallIcon", null))
    }

    @Test
    fun aStoredNameThatNoLongerResolvesFallsBackToTheBuiltInIcon() {
        preferences.edit().putString("notificationSmallIcon", "${application.packageName}:drawable/removed_in_an_update").commit()
        assertEquals(R.drawable.reception_symbol_notifications, deliver())
        assertNull(Reception.notificationSmallIcon)
    }

    @Test
    fun aChatOpenedWhileTheStoredIconIsReadGetsNoNotification() {
        // The first delivery reads the app's declared languages, so the next one waits only for the stored icon.
        Reception.notificationSmallIcon = R.drawable.reception_symbol_chat
        deliver()
        settle()
        restartProcess()
        notifications.cancelAll()
        val release = CountDownLatch(1)
        Storage.launch { release.await(5, TimeUnit.SECONDS) }
        try {
            assertTrue(Reception.handlePushMessage(application, mapOf("reception" to "c1", "title" to "Team", "body" to "Hi")))
            shadowOf(Looper.getMainLooper()).idle()
            ReceptionState.isChatOpen.value = true
            release.countDown()
            settle()
            assertEquals(0, notifications.activeNotifications.size)
        } finally {
            release.countDown()
            ReceptionState.isChatOpen.value = false
        }
    }
}
