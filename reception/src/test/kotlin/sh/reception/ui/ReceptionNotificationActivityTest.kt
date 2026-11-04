package sh.reception.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAppTask
import sh.reception.Reception
import sh.reception.ReceptionInternalApi
import sh.reception.ReceptionState
import sh.reception.client.ReceptionAPI
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.PushNotifications
import sh.reception.viewmodel.ChatHarness
import java.time.Duration

/** A notification tap brings the app forward and opens the chat over whatever activity resumes; repeats do nothing. */
@RunWith(RobolectricTestRunner::class)
class ReceptionNotificationActivityTest {
    private val harness = ChatHarness()
    private val application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        val launcher = ComponentName(application.packageName, "Launcher")
        val packages = shadowOf(application.packageManager)
        packages.addActivityIfNotPresent(launcher)
        packages.addIntentFilterForActivity(launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        harness.start()
    }

    @OptIn(ReceptionInternalApi::class)
    @After
    fun tearDown() {
        Reception.setNotificationRoutingEnabled(true)
        harness.stop()
    }

    /**
     * Taps notification [key]; [taskRoot] when no task of the app existed. Returns the launch the trampoline started.
     */
    private fun tap(key: String, taskRoot: Boolean = false): Intent? {
        val intent = Intent(application, ReceptionNotificationActivity::class.java)
            .putExtra(PushNotifications.EXTRA_CONVERSATION, "c1")
            .putExtra(PushNotifications.EXTRA_NOTIFICATION, key)
        val controller = Robolectric.buildActivity(ReceptionNotificationActivity::class.java, intent)
        shadowOf(controller.get()).setIsTaskRoot(taskRoot)
        val trampoline = controller.create().get()
        assertTrue(trampoline.isFinishing)
        return shadowOf(trampoline).nextStartedActivity
    }

    /** An activity of the host resumes (here handed over as a plugin does); returns the chat it presented, if any. */
    private fun resume(host: Activity): Intent? {
        ApplicationLifecycle.noteCurrentActivity(host)
        shadowOf(Looper.getMainLooper()).idle()
        val started = shadowOf(host).nextStartedActivity
        // The presenter treats a sheet that was started but never created as presented for a moment.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        return started
    }

    @Test
    fun aTapStartsTheAppOnlyWithoutATaskAndOpensTheChatOverTheTopActivity() {
        val launch = tap("n0", taskRoot = true)
        assertEquals("Launcher", launch?.component?.className)
        assertTrue(requireNotNull(launch).flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // In the app's existing task, finishing reveals its top activity; another launcher activity would stack on it.
        assertNull(tap("n1"))
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertEquals(ReceptionChatActivity::class.java.name, resume(host)?.component?.className)
    }

    /** A task of the app in Recents, rooted at [root], with [records] activity records. */
    private fun appTask(id: Int, root: String, records: Int): ActivityManager.AppTask {
        val task = ShadowAppTask.newInstance()
        shadowOf(task).setTaskInfo(ActivityManager.RecentTaskInfo().apply {
            @Suppress("DEPRECATION")
            persistentId = id
            taskId = id
            numActivities = records
            baseIntent = Intent().setComponent(ComponentName(application.packageName, root))
        })
        return task
    }

    private fun recents(vararg tasks: ActivityManager.AppTask) {
        val manager = application.getSystemService(ActivityManager::class.java)
        shadowOf(manager).setAppTasks(tasks.toList())
    }

    @Test
    fun aTaskWhoseProcessDiedIsBroughtBackInsteadOfASecondOne() {
        // The launcher activity declares another affinity (Flutter's `taskAffinity=""`), so the tap got a new task.
        for (records in listOf(0, 2)) {
            // A card restored from disk has no activity records until it is brought back.
            val existing = appTask(42, "Launcher", records)
            recents(existing)
            assertNull(tap("r$records", taskRoot = true))
            assertTrue(shadowOf(existing).hasMovedToFront())
        }
    }

    @Test
    fun aLeftoverTrampolineTaskIsNotRestoredAndTheAppStarts() {
        val leftover = appTask(43, ReceptionNotificationActivity::class.java.name, 0)
        val other = appTask(44, "sh.reception.Other", 1)
        recents(leftover)
        assertEquals("Launcher", tap("t1", taskRoot = true)?.component?.className)
        assertFalse(shadowOf(leftover).hasMovedToFront())
        recents(leftover, other)
        assertNull(tap("t2", taskRoot = true))
        assertTrue(shadowOf(other).hasMovedToFront())
    }

    @Test
    fun aRepeatedTapIsIgnoredAndAnotherNotificationOpensAgain() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        tap("n2")
        assertEquals(ReceptionChatActivity::class.java.name, resume(host)?.component?.className)
        tap("n2")
        assertNull(resume(host))
        tap("n3")
        assertEquals(ReceptionChatActivity::class.java.name, resume(host)?.component?.className)
    }

    @OptIn(ReceptionInternalApi::class)
    @Test
    fun aPluginsActivityPresentsTheChatBeforeItsNextResume() {
        // Created and started before configure registered the lifecycle callbacks, as under Flutter.
        val host = Robolectric.buildActivity(Activity::class.java).create().start().get()
        Reception.openChat()
        assertNull(shadowOf(host).nextStartedActivity)
        // Another test's activity may still be the current one and have taken that request.
        ChatPresenter.dismiss()
        Reception.noteCurrentActivity(host)
        Reception.openChat()
        assertEquals(ReceptionChatActivity::class.java.name, shadowOf(host).nextStartedActivity?.component?.className)
    }

    @OptIn(ReceptionInternalApi::class)
    @Test
    fun turningRoutingOffDiscardsTheTapThatWaitsForConfigure() {
        ReceptionState.resetForTests()
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        tap("p1", taskRoot = true)
        // The plugin's configure(handlesNotifications: false), then a later opt-in: the tap stays discarded.
        Reception.setNotificationRoutingEnabled(false)
        Reception.setNotificationRoutingEnabled(true)
        ReceptionAPI.serviceUrl = harness.server.url("/")
        ApplicationLifecycle.noteCurrentActivity(host)
        ReceptionState.configure(application, ChatHarness.APP_ID)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @OptIn(ReceptionInternalApi::class)
    @Test
    fun aTapWhileRoutingIsOffOnlyBringsTheAppForwardAndTurningItOnRoutesTheNext() {
        Reception.setNotificationRoutingEnabled(false)
        assertEquals("Launcher", tap("d1", taskRoot = true)?.component?.className)
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        tap("d2")
        assertNull(resume(host))
        Reception.setNotificationRoutingEnabled(true)
        tap("d3")
        assertEquals(ReceptionChatActivity::class.java.name, resume(host)?.component?.className)
    }
}
