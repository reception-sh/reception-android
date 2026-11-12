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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppTask
import sh.reception.Log
import sh.reception.Reception
import sh.reception.ReceptionChat
import sh.reception.ReceptionEvent
import sh.reception.ReceptionResets
import sh.reception.ReceptionState
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.PresentationRecord
import sh.reception.client.ReceptionAPI
import sh.reception.client.Storage
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A sheet whose presentation was closed or superseded before it was created finishes at once. */
@RunWith(RobolectricTestRunner::class)
class ChatPresentationTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() {
        Log.handler = null
        Log.level = Reception.LogLevel.INFO
        harness.stop()
    }

    private fun launch(open: () -> Unit): Intent? {
        harness.start()
        val host = TestActivities.build(Activity::class.java).setup().get()
        // Robolectric gives each test a new application; the lifecycle callbacks stay on the first one.
        ApplicationLifecycle.onActivityResumed(host)
        open()
        harness.settle()
        return shadowOf(host).nextStartedActivity
    }

    private fun created(intent: Intent?): ReceptionChatActivity =
        TestActivities.build(ReceptionChatActivity::class.java, requireNotNull(intent)).create().get()

    @Test
    fun aSheetClosedInTheSameCallbackAsItsOpeningIsNotStarted() {
        harness.start()
        val host = TestActivities.build(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(host)
        // Held across both calls: unheld, a fast save can finish inside open() (withContext returns at once when its
        // block is done first), and the sheet would start before the close.
        val release = CountDownLatch(1)
        val storageHeld = Storage.launch { release.await(5, TimeUnit.SECONDS) }
        ReceptionChat.open()
        ReceptionChat.close()
        release.countDown()
        runBlocking { storageHeld.join() }
        harness.settle()
        assertNull(shadowOf(host).nextStartedActivity)
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun aSheetClosedAfterItStartedFinishesWhenCreated() {
        val intent = launch { ReceptionChat.open() }
        ReceptionChat.close()
        assertTrue(created(intent).isFinishing)
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun theSheetStartsOnceItsRecordIsOnDiskAndOnlyOverTheScreenThatAskedForIt() {
        harness.start()
        val host = TestActivities.build(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(host)
        val release = CountDownLatch(1)
        val storageHeld = Storage.launch { release.await(5, TimeUnit.SECONDS) }
        ReceptionChat.open()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("sheet started before its record was saved", shadowOf(host).nextStartedActivity)
        assertTrue(ChatPresenter.isPresented)
        release.countDown()
        runBlocking { storageHeld.join() }
        harness.settle()
        val intent = shadowOf(host).nextStartedActivity
        val token = intent?.getStringExtra(SheetLaunch.EXTRA_PRESENTATION)
        assertEquals(token, PresentationRecord.load(RuntimeEnvironment.getApplication())?.token)
        ReceptionChat.close()
        harness.settle()
        // Another screen resumed while the record was being saved: the sheet does not start over either.
        val held = CountDownLatch(1)
        val storageHeldAgain = Storage.launch { held.await(5, TimeUnit.SECONDS) }
        ReceptionChat.open()
        val other = TestActivities.build(Activity::class.java).setup().get()
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
        val cancellations = mutableListOf<String>()
        Log.level = Reception.LogLevel.DEBUG
        Log.handler = { _, line -> if (line.startsWith("Chat presentation cancelled")) cancellations += line }
        for (destroyed in listOf(false, true)) {
            val controller = TestActivities.build(Activity::class.java).setup()
            val host = controller.get()
            ApplicationLifecycle.onActivityResumed(host)
            val release = CountDownLatch(1)
            val storageHeld = Storage.launch { release.await(5, TimeUnit.SECONDS) }
            ReceptionChat.open()
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
        // One line per cancellation, with its reason.
        assertEquals(List(2) { "Chat presentation cancelled, the screen closed before it started" }, cancellations)
    }

    /**
     * On API 34, not the default 35: its attached window's frame leaves Compose's shared state in a state where
     * `EmbeddedKeyboardWindowsTest`'s Compose `Dialog`, later in the same Robolectric sandbox, never composes.
     */
    @Test
    @Config(sdk = [34])
    fun aSheetClosedBeforeItsFirstFrameNeverReportsTheChatOpen() {
        val events = mutableListOf<ReceptionEvent>()
        ReceptionState.onEvent = { events += it }
        val intent = launch { ReceptionChat.open() }
        val controller = TestActivities.build(ReceptionChatActivity::class.java, requireNotNull(intent))
            .create().start().resume()
        // closeChat() between the sheet's start and its first frame, as just after an automation opened it: the sheet
        // finishes at once, and the first frame that still comes on its attached window composes nothing
        // (`ReceptionChatActivity.show`).
        ReceptionChat.close()
        assertTrue(controller.get().isFinishing)
        controller.visible()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
        assertEquals(emptyList<ReceptionEvent>(), events)
        assertFalse(ReceptionState.isChatOpen.value)
        ReceptionState.onEvent = null
    }

    @Test
    fun anOpenedSheetIsPresented() {
        val sheet = created(launch { ReceptionChat.open() })
        assertFalse(sheet.isFinishing)
        assertTrue(ChatPresenter.isPresented)
        ReceptionChat.close()
    }

    @Test
    fun aSheetCoveredByAnotherActivityIsBroughtToTheFront() {
        // Created but not started: what a sheet under another activity looks like to the presenter.
        val sheet = created(launch { ReceptionChat.open() })
        assertFalse(ReceptionState.isChatOpen.value)
        val cover = TestActivities.build(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(cover)
        ReceptionChat.open()
        harness.settle()
        val started = shadowOf(cover).nextStartedActivity
        assertEquals(ReceptionChatActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertFalse(started.hasExtra(SheetLaunch.EXTRA_PRESENTATION))
        assertFalse(sheet.isFinishing)
        ReceptionChat.close()
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
        val sheet = created(launch { ReceptionChat.open() })
        val sheetTask = ShadowAppTask.newInstance()
        shadowOf(sheetTask).setTaskInfo(ActivityManager.RecentTaskInfo().apply {
            @Suppress("DEPRECATION")
            persistentId = sheet.taskId
            taskId = sheet.taskId
            numActivities = 3
        })
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(sheetTask))
        val front = TestActivities.build(OtherTaskActivity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(front)
        ReceptionChat.open()
        // Task A comes to the front; a start from task B would only land in B.
        assertTrue(shadowOf(sheetTask).hasMovedToFront())
        assertNull(shadowOf(front).nextStartedActivity)
        return sheet
    }

    /** The cover above the sheet resumes in task A; returns what it started. */
    private fun coverResumed(): Intent? {
        val cover = TestActivities.build(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(cover)
        return shadowOf(cover).nextStartedActivity
    }

    private fun assertReordered(started: Intent?) {
        assertEquals(ReceptionChatActivity::class.java.name, started?.component?.className)
        assertTrue(requireNotNull(started).flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertFalse(started.hasExtra(SheetLaunch.EXTRA_PRESENTATION))
    }

    @Test
    fun aSheetCoveredInItsOwnTaskWhileAnotherTaskIsInFrontIsReorderedWithinItsTask() {
        val sheet = openFromAnotherTask()
        assertReordered(coverResumed())
        assertFalse(sheet.isFinishing)
        ReceptionChat.close()
    }

    @Test
    fun anUnrelatedResumeBeforeTheSheetsTaskResumesKeepsTheReorder() {
        val sheet = openFromAnotherTask()
        // Another task's activity resumes first, or a plugin hands its activity over again.
        val other = TestActivities.build(OtherTaskActivity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(other)
        ApplicationLifecycle.noteCurrentActivity(other)
        assertNull(shadowOf(other).nextStartedActivity)
        assertReordered(coverResumed())
        assertFalse(sheet.isFinishing)
        // Done once the sheet's task resumed: a later resume there reorders nothing.
        assertNull(coverResumed())
        ReceptionChat.close()
    }

    @Test
    fun aClosedSheetIsNotReorderedWhenItsTaskResumes() {
        openFromAnotherTask()
        ReceptionChat.close()
        assertNull(coverResumed())
    }

    @Test
    fun aLogoutDuringTheLaunchFinishesTheQueuedSheet() {
        val intent = launch { ReceptionChat.open() }
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
        val sheet = TestActivities.build(ReceptionChatActivity::class.java, requireNotNull(intent)).create(Bundle()).get()
        harness.mainLoopUntil { sheet.isFinishing || ChatPresenter.isPresented }
        return sheet
    }

    @Test
    fun aSheetOnScreenWhenTheProcessDiedIsRestored() {
        val intent = launch { ReceptionChat.open() }
        created(intent)
        processRestarted()
        val sheet = restored(intent)
        assertFalse(sheet.isFinishing)
        assertTrue(ChatPresenter.isPresented)
        // Closing it forgets the record: the next process restores nothing.
        ReceptionChat.close()
        processRestarted()
        assertTrue(restored(intent).isFinishing)
    }

    @Test
    fun aSheetIsNotRestoredAfterALogoutInTheNewProcess() {
        val intent = launch { ReceptionChat.open() }
        created(intent)
        processRestarted()
        ReceptionResets.resetLocal()
        assertTrue(restored(intent).isFinishing)
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun aSupersededSheetIsNotRestored() {
        val first = launch { ReceptionChat.open() }
        ReceptionChat.close()
        val host = TestActivities.build(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(host)
        ReceptionChat.open()
        harness.settle()
        val second = shadowOf(host).nextStartedActivity
        created(second)
        processRestarted()
        assertTrue(restored(first).isFinishing)
        assertFalse(restored(second).isFinishing)
        ReceptionChat.close()
    }

    @Test
    fun aSheetWithoutAPresentationFinishes() {
        launch {}
        assertTrue(created(Intent(RuntimeEnvironment.getApplication(), ReceptionChatActivity::class.java)).isFinishing)
    }
}
