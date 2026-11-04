package sh.reception.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
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
import org.robolectric.shadows.ShadowAppTask
import sh.reception.Reception
import sh.reception.ReceptionInternalApi
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

    @After
    fun tearDown() = harness.stop()

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

    @Test
    fun aTaskWhoseProcessDiedIsBroughtBackInsteadOfASecondOne() {
        // The launcher activity declares another affinity (Flutter's `taskAffinity=""`), so the tap got a new task.
        val existing = ShadowAppTask.newInstance()
        shadowOf(existing).setTaskInfo(ActivityManager.RecentTaskInfo().apply {
            @Suppress("DEPRECATION")
            persistentId = 42
            taskId = 42
            numActivities = 2
        })
        val manager = application.getSystemService(ActivityManager::class.java)
        shadowOf(manager).setAppTasks(listOf(existing))
        assertNull(tap("n4", taskRoot = true))
        assertTrue(shadowOf(existing).hasMovedToFront())
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
}
