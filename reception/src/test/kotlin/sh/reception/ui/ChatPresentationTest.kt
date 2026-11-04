package sh.reception.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAppTask
import sh.reception.ReceptionResets
import sh.reception.ReceptionState
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.PresentationRecord
import sh.reception.client.ReceptionAPI
import sh.reception.client.Storage
import sh.reception.viewmodel.ChatHarness
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A sheet whose presentation was closed or superseded before it was created finishes at once. */
@RunWith(RobolectricTestRunner::class)
class ChatPresentationTest {
    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    private fun launch(open: () -> Unit): Intent? {
        harness.start()
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        // Robolectric gives each test a new application; the lifecycle callbacks stay on the first one.
        ApplicationLifecycle.onActivityResumed(host)
        open()
        harness.settle()
        return shadowOf(host).nextStartedActivity
    }

    private fun created(intent: Intent?): ReceptionChatActivity =
        Robolectric.buildActivity(ReceptionChatActivity::class.java, requireNotNull(intent)).create().get()

    @Test
    fun aSheetClosedInTheSameCallbackAsItsOpeningIsNotStarted() {
        val intent = launch {
            ChatPresenter.open()
            ChatPresenter.close()
        }
        assertNull(intent)
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun aSheetClosedAfterItStartedFinishesWhenCreated() {
        val intent = launch { ChatPresenter.open() }
        ChatPresenter.close()
        assertTrue(created(intent).isFinishing)
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun theSheetStartsOnceItsRecordIsOnDiskAndOnlyOverTheScreenThatAskedForIt() {
        harness.start()
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(host)
        val release = CountDownLatch(1)
        val storageHeld = Storage.launch { release.await(5, TimeUnit.SECONDS) }
        ChatPresenter.open()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("sheet started before its record was saved", shadowOf(host).nextStartedActivity)
        assertTrue(ChatPresenter.isPresented)
        release.countDown()
        runBlocking { storageHeld.join() }
        harness.settle()
        val intent = shadowOf(host).nextStartedActivity
        val token = intent?.getStringExtra(ChatPresenter.EXTRA_PRESENTATION)
        assertEquals(token, PresentationRecord.load(RuntimeEnvironment.getApplication())?.token)
        ChatPresenter.close()
        harness.settle()
        // Another screen resumed while the record was being saved: the sheet does not start over either.
        val held = CountDownLatch(1)
        val storageHeldAgain = Storage.launch { held.await(5, TimeUnit.SECONDS) }
        ChatPresenter.open()
        val other = Robolectric.buildActivity(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(other)
        held.countDown()
        runBlocking { storageHeldAgain.join() }
        harness.settle()
        assertNull(shadowOf(host).nextStartedActivity)
        assertNull(shadowOf(other).nextStartedActivity)
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun aScreenThatFinishesWhileTheRecordIsSavedGetsNoSheet() {
        harness.start()
        for (destroyed in listOf(false, true)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val host = controller.get()
            ApplicationLifecycle.onActivityResumed(host)
            val release = CountDownLatch(1)
            val storageHeld = Storage.launch { release.await(5, TimeUnit.SECONDS) }
            ChatPresenter.open()
            // The host closes itself before its pause arrives, so it is still the current activity.
            host.finish()
            if (destroyed) controller.destroy()
            release.countDown()
            runBlocking { storageHeld.join() }
            harness.settle()
            assertNull("sheet started over a finishing screen", shadowOf(host).nextStartedActivity)
            assertFalse(ChatPresenter.isPresented)
            assertNull(PresentationRecord.load(RuntimeEnvironment.getApplication()))
        }
    }

    @Test
    fun anOpenedSheetIsPresented() {
        val sheet = created(launch { ChatPresenter.open() })
        assertFalse(sheet.isFinishing)
        assertTrue(ChatPresenter.isPresented)
        ChatPresenter.close()
    }

    @Test
    fun aSheetCoveredByAnotherActivityIsBroughtToTheFront() {
        // Created but not started: what a sheet under another activity looks like to the presenter.
        val sheet = created(launch { ChatPresenter.open() })
        assertFalse(ReceptionState.isChatOpen.value)
        val cover = Robolectric.buildActivity(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(cover)
        ChatPresenter.open()
        harness.settle()
        val started = shadowOf(cover).nextStartedActivity
        assertEquals(ReceptionChatActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertFalse(started.hasExtra(ChatPresenter.EXTRA_PRESENTATION))
        assertFalse(sheet.isFinishing)
        ChatPresenter.close()
    }

    /** An activity of the app in another task than the sheet's, like one started with `NEW_TASK` from elsewhere. */
    class OtherTaskActivity : Activity() {
        override fun getTaskId(): Int = 7
    }

    /**
     * Task A: host, sheet, then a cover above the sheet (all task 0 here); task B in front. Opening the chat from B
     * moves task A to the front; returns the sheet.
     */
    private fun openFromAnotherTask(): ReceptionChatActivity {
        val sheet = created(launch { ChatPresenter.open() })
        val sheetTask = ShadowAppTask.newInstance()
        shadowOf(sheetTask).setTaskInfo(ActivityManager.RecentTaskInfo().apply {
            @Suppress("DEPRECATION")
            persistentId = sheet.taskId
            taskId = sheet.taskId
            numActivities = 3
        })
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(sheetTask))
        val front = Robolectric.buildActivity(OtherTaskActivity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(front)
        ChatPresenter.open()
        // Task A comes to the front; a start from task B would only land in B.
        assertTrue(shadowOf(sheetTask).hasMovedToFront())
        assertNull(shadowOf(front).nextStartedActivity)
        return sheet
    }

    /** The cover above the sheet resumes in task A; returns what it started. */
    private fun coverResumed(): Intent? {
        val cover = Robolectric.buildActivity(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(cover)
        return shadowOf(cover).nextStartedActivity
    }

    private fun assertReordered(started: Intent?) {
        assertEquals(ReceptionChatActivity::class.java.name, started?.component?.className)
        assertTrue(requireNotNull(started).flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertFalse(started.hasExtra(ChatPresenter.EXTRA_PRESENTATION))
    }

    @Test
    fun aSheetCoveredInItsOwnTaskWhileAnotherTaskIsInFrontIsReorderedWithinItsTask() {
        val sheet = openFromAnotherTask()
        assertReordered(coverResumed())
        assertFalse(sheet.isFinishing)
        ChatPresenter.close()
    }

    @Test
    fun anUnrelatedResumeBeforeTheSheetsTaskResumesKeepsTheReorder() {
        val sheet = openFromAnotherTask()
        // Another task's activity resumes first, or a plugin hands its activity over again.
        val other = Robolectric.buildActivity(OtherTaskActivity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(other)
        ApplicationLifecycle.noteCurrentActivity(other)
        assertNull(shadowOf(other).nextStartedActivity)
        assertReordered(coverResumed())
        assertFalse(sheet.isFinishing)
        // Done once the sheet's task resumed: a later resume there reorders nothing.
        assertNull(coverResumed())
        ChatPresenter.close()
    }

    @Test
    fun aClosedSheetIsNotReorderedWhenItsTaskResumes() {
        openFromAnotherTask()
        ChatPresenter.close()
        assertNull(coverResumed())
    }

    @Test
    fun aLogoutDuringTheLaunchFinishesTheQueuedSheet() {
        val intent = launch { ChatPresenter.open() }
        ReceptionResets.resetLocal()
        assertTrue(created(intent).isFinishing)
    }

    /** The process dies with the sheet on screen and starts again; the app configures in `Application.onCreate`. */
    private fun processRestarted() {
        runBlocking { Storage.run {} }
        ReceptionState.resetForTests()
        ReceptionAPI.serviceUrl = harness.server.url("/")
        ReceptionState.configure(RuntimeEnvironment.getApplication(), ChatHarness.APP_ID)
    }

    /** Android recreates the sheet from its saved state; answers it once the presenter decided. */
    private fun restored(intent: Intent?): ReceptionChatActivity {
        val sheet = Robolectric.buildActivity(ReceptionChatActivity::class.java, requireNotNull(intent)).create(Bundle()).get()
        harness.mainLoopUntil { sheet.isFinishing || ChatPresenter.isPresented }
        return sheet
    }

    @Test
    fun aSheetOnScreenWhenTheProcessDiedIsRestored() {
        val intent = launch { ChatPresenter.open() }
        created(intent)
        processRestarted()
        val sheet = restored(intent)
        assertFalse(sheet.isFinishing)
        assertTrue(ChatPresenter.isPresented)
        // Closing it forgets the record: the next process restores nothing.
        ChatPresenter.close()
        processRestarted()
        assertTrue(restored(intent).isFinishing)
    }

    @Test
    fun aSheetIsNotRestoredAfterALogoutInTheNewProcess() {
        val intent = launch { ChatPresenter.open() }
        created(intent)
        processRestarted()
        ReceptionResets.resetLocal()
        assertTrue(restored(intent).isFinishing)
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun aSupersededSheetIsNotRestored() {
        val first = launch { ChatPresenter.open() }
        ChatPresenter.close()
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(host)
        ChatPresenter.open()
        harness.settle()
        val second = shadowOf(host).nextStartedActivity
        created(second)
        processRestarted()
        assertTrue(restored(first).isFinishing)
        assertFalse(restored(second).isFinishing)
        ChatPresenter.close()
    }

    /** Flutter and React Native configure from Dart/JS, after Android recreated the sheet. */
    private fun processRestartedWithoutConfigure() {
        runBlocking { Storage.run {} }
        ReceptionState.resetForTests()
        ReceptionAPI.serviceUrl = harness.server.url("/")
    }

    private fun recreated(intent: Intent?): ReceptionChatActivity =
        Robolectric.buildActivity(ReceptionChatActivity::class.java, requireNotNull(intent)).create(Bundle()).get()

    @Test
    fun aRestoredSheetWaitsForAConfigureThatComesLate() {
        val intent = launch { ChatPresenter.open() }
        created(intent)
        processRestartedWithoutConfigure()
        val sheet = recreated(intent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(sheet.isFinishing)
        assertFalse(ChatPresenter.isPresented)
        ReceptionState.configure(RuntimeEnvironment.getApplication(), ChatHarness.APP_ID)
        harness.mainLoopUntil { sheet.isFinishing || ChatPresenter.isPresented }
        assertFalse(sheet.isFinishing)
        ChatPresenter.close()
    }

    @Test
    fun aRestoredSheetFinishesWhenNoConfigureComes() {
        val intent = launch { ChatPresenter.open() }
        created(intent)
        processRestartedWithoutConfigure()
        val sheet = recreated(intent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_900))
        assertFalse(sheet.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(sheet.isFinishing)
        // A configure after the wait ended restores nothing.
        ReceptionState.configure(RuntimeEnvironment.getApplication(), ChatHarness.APP_ID)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun aLateConfigureFollowedByALogoutDoesNotRestore() {
        val intent = launch { ChatPresenter.open() }
        created(intent)
        processRestartedWithoutConfigure()
        val sheet = recreated(intent)
        ReceptionState.configure(RuntimeEnvironment.getApplication(), ChatHarness.APP_ID)
        ReceptionResets.resetLocal()
        harness.mainLoopUntil { sheet.isFinishing || ChatPresenter.isPresented }
        assertTrue(sheet.isFinishing)
    }

    @Test
    fun aSheetWithoutAPresentationFinishes() {
        launch {}
        assertTrue(created(Intent(RuntimeEnvironment.getApplication(), ReceptionChatActivity::class.java)).isFinishing)
    }
}
