package sh.reception.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import sh.reception.PendingChatOpen
import sh.reception.Reception
import sh.reception.ReceptionChat
import sh.reception.ReceptionError
import sh.reception.ReceptionInternalApi
import sh.reception.ReceptionEvent
import sh.reception.ReceptionPush
import sh.reception.ReceptionResets
import sh.reception.ReceptionState
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.PresentationRecord
import sh.reception.client.ReceptionAPI
import sh.reception.client.Storage
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The process died with the sheet on screen, and the new one configures only later (Flutter, React Native): Android
 * recreates the sheet first, which finishes at once, and `configure` presents the chat again over the host.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ReceptionInternalApi::class)
class DeferredRestoreTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()
    private val application get() = RuntimeEnvironment.getApplication()

    /** Deep sleep: the elapsed clock moves on, the main looper's uptime does not. */
    private var slept = 0L

    private fun sleep(millis: Long) {
        slept += millis
        DeferredRestore.clock = { SystemClock.elapsedRealtime() + slept }
    }

    @After
    fun tearDown() {
        DeferredRestore.clock = SystemClock::elapsedRealtime
        ReceptionState.onEvent = null
        harness.stop()
    }

    /** The sheet open in the first process; answers its launch intent. */
    private fun openTheSheet(): Intent {
        harness.start()
        val host = TestActivities.build(Activity::class.java).setup().get()
        ApplicationLifecycle.onActivityResumed(host)
        ReceptionChat.open()
        harness.settle()
        val intent = requireNotNull(shadowOf(host).nextStartedActivity)
        TestActivities.build(ReceptionChatActivity::class.java, intent).create()
        return intent
    }

    /** The process died and starts again without configure; Android recreates the sheet from its saved state. */
    private fun restoredBeforeConfigure(intent: Intent): ReceptionChatActivity {
        processDied()
        return TestActivities.build(ReceptionChatActivity::class.java, intent).create(Bundle()).get()
    }

    private fun processDied() {
        runBlocking { Storage.run {} }
        ReceptionState.resetForTests()
        ReceptionAPI.serviceUrl = harness.server.url("/")
    }

    /** The host's activity of the new process resumes (its own callback, or a plugin's `noteCurrentActivity`). */
    private fun resumedHost(): ComponentActivity =
        TestActivities.build(ComponentActivity::class.java).setup().get().also(ApplicationLifecycle::onActivityResumed)

    private fun configure(appId: String = ChatHarness.APP_ID) {
        ReceptionState.configure(application, appId)
        harness.settle()
    }

    private fun token(intent: Intent?): String? = intent?.getStringExtra(SheetLaunch.EXTRA_PRESENTATION)

    private fun startedSheets(host: Activity): List<Intent> =
        generateSequence { shadowOf(host).nextStartedActivity }.toList()

    private fun recordedToken(): String? = runBlocking { Storage.run { PresentationRecord.load(application)?.token } }

    @Test
    fun aSheetRestoredBeforeConfigureFinishesAtOnceAndOpensAgainOnConfigure() {
        val intent = openTheSheet()
        val sheet = restoredBeforeConfigure(intent)
        assertTrue("the restored sheet must not stay on top of the host", sheet.isFinishing)
        val host = resumedHost()
        assertEquals(emptyList<Intent>(), startedSheets(host))
        configure()
        val again = startedSheets(host).single()
        assertEquals(token(intent), token(again))
        assertTrue("it opens open, without the entry", again.getBooleanExtra(SheetLaunch.EXTRA_RETURNING, false))
        val events = mutableListOf<ReceptionEvent>()
        ReceptionState.onEvent = { events += it }
        val shown = TestActivities.build(ReceptionChatActivity::class.java, again).setup().get()
        harness.mainLoopUntil { ReceptionState.isChatOpen.value }
        assertFalse(shown.isFinishing)
        assertEquals(1, events.count { it == ReceptionEvent.ChatOpened })
        assertEquals(token(intent), recordedToken())
        ReceptionChat.close()
    }

    /** Android destroys the finished sheet a moment later, after the restore started (0.5–0.7 s on Android 16). */
    @Test
    fun theFinishedSheetsLateDestructionKeepsTheRestoredPresentation() {
        val intent = openTheSheet()
        processDied()
        val finished = TestActivities.build(ReceptionChatActivity::class.java, intent).create(Bundle())
        val host = resumedHost()
        configure()
        val again = startedSheets(host).single()
        TestActivities.build(ReceptionChatActivity::class.java, again).create()
        finished.destroy()
        harness.settle()
        assertEquals(token(intent), ChatPresenter.presentation)
        assertEquals(token(intent), recordedToken())
        ReceptionChat.close()
    }

    @Test
    fun theHostResumingAfterConfigureGetsTheSheet() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val controller = TestActivities.build(ComponentActivity::class.java).create()
        configure()
        val host = controller.start().resume().get()
        ApplicationLifecycle.onActivityResumed(host)
        harness.settle()
        assertEquals(token(intent), token(startedSheets(host).single()))
        ReceptionChat.close()
    }

    @Test
    fun aConfigureJustInsideTheBoundStillRestores() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_900))
        configure()
        assertEquals(token(intent), token(startedSheets(host).single()))
        ReceptionChat.close()
    }

    @Test
    fun noConfigureWithinTheBoundDropsThePresentationAndItsRecord() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000))
        assertNull(recordedToken())
        configure()
        assertEquals(emptyList<Intent>(), startedSheets(host))
        assertFalse(ChatPresenter.isPresented)
    }

    /** The device slept 20 s after the recreation: the bound counts that, also without the timer firing (A28-2). */
    @Test
    fun aConfigureAfterDeepSleepPastTheBoundDoesNotRestore() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        sleep(20_000)
        configure()
        assertEquals(emptyList<Intent>(), startedSheets(host))
        assertNull(recordedToken())
    }

    @Test
    fun theBoundIsCheckedWhenTheRecordReadReturns() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        val release = CountDownLatch(1)
        val storageHeld = Storage.launch { release.await(5, TimeUnit.SECONDS) }
        ReceptionState.configure(application, ChatHarness.APP_ID)
        sleep(20_000)
        release.countDown()
        runBlocking { storageHeld.join() }
        harness.settle()
        assertEquals(emptyList<Intent>(), startedSheets(host))
        assertNull(recordedToken())
    }

    @Test
    fun theBoundIsCheckedRightBeforeTheSheetStarts() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val controller = TestActivities.build(ComponentActivity::class.java).create()
        configure()
        sleep(20_000)
        val host = controller.start().resume().get()
        ApplicationLifecycle.onActivityResumed(host)
        harness.settle()
        assertEquals(emptyList<Intent>(), startedSheets(host))
        assertNull(recordedToken())
    }

    @Test
    fun aConfigureWithAnotherAppIdDoesNotRestore() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        configure("app_otherOtherOtherOtherOt")
        assertEquals(emptyList<Intent>(), startedSheets(host))
        assertNull(recordedToken())
    }

    /** [after] runs right after configure, before the record was read on the storage thread. */
    private fun restoreWith(after: () -> Unit): List<Intent> {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        val release = CountDownLatch(1)
        val storageHeld = Storage.launch { release.await(5, TimeUnit.SECONDS) }
        ReceptionState.configure(application, ChatHarness.APP_ID)
        after()
        release.countDown()
        runBlocking { storageHeld.join() }
        harness.settle()
        return startedSheets(host).onEach { assertNotEquals("restored", token(intent), token(it)) }
    }

    /**
     * The bound passes while the record is read (storage held) and the host meanwhile configured and opened the chat,
     * whose record save is queued behind that read: the expired note must not clear the newer record (audit A28-1).
     */
    @Test
    fun anExpiredNoteLeavesANewerPresentationsRecordAlone() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val storageHeld = Storage.launch { entered.countDown(); release.await(30, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            ReceptionState.configure(application, ChatHarness.APP_ID)
            ReceptionChat.open()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_001))
        } finally {
            release.countDown()
        }
        runBlocking { storageHeld.join() }
        harness.settle()
        val launched = startedSheets(host).single()
        assertNotEquals("restored", token(intent), token(launched))
        assertEquals("expired note must leave the newer record alone", token(launched), recordedToken())
        ReceptionChat.close()
    }

    /** [call] comes from the host after the sheet finished and before `configure` (audit A28-3). */
    private fun cancelledBeforeConfigure(call: () -> Unit) {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        call()
        configure()
        assertEquals(emptyList<Intent>(), startedSheets(host))
        assertFalse(ChatPresenter.isPresented)
        assertNull(recordedToken())
    }

    @Test
    fun aCloseBeforeConfigureCancelsTheRestore() = cancelledBeforeConfigure { Reception.closeChat() }

    @Test
    fun aLogoutBeforeConfigureCancelsTheRestore() = cancelledBeforeConfigure { Reception.logout() }

    @Test
    fun aDeletionBeforeConfigureCancelsTheRestore() = cancelledBeforeConfigure {
        var error: ReceptionError? = ReceptionError("not_called", 0)
        Reception.deleteData { error = it }
        harness.mainLoopUntil { error == null }
    }

    @Test
    fun aCloseRightAfterConfigureIsNotUndone() {
        assertEquals(emptyList<Intent>(), restoreWith { ReceptionChat.close() })
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun aLogoutRightAfterConfigureIsNotUndone() {
        assertEquals(emptyList<Intent>(), restoreWith { ReceptionResets.resetLocal() })
    }

    @Test
    fun anOpenChatBeforeTheRestoreGivesOneSheet() {
        assertEquals(1, restoreWith { ReceptionChat.open() }.size)
        ReceptionChat.close()
    }

    @Test
    fun anOpenChatAfterTheRestoreGivesNoSecondSheet() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        configure()
        ReceptionChat.open()
        harness.settle()
        assertEquals(listOf(token(intent)), startedSheets(host).map(::token))
        ReceptionChat.close()
    }

    @Test
    fun aNotificationTapBeforeConfigureGivesOneSheet() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        ReceptionPush.notificationTapped("tap", "conversation")
        configure()
        // The tap's open runs shortly after configure, when the restored sheet was already started.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        harness.settle()
        assertEquals(listOf(token(intent)), startedSheets(host).map(::token))
        ReceptionChat.close()
    }

    @Test
    fun aTapWaitingForAnActivityGivesOneSheet() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val controller = TestActivities.build(ComponentActivity::class.java).create()
        configure()
        PendingChatOpen.request(PendingChatOpen.Source.NOTIFICATION_TAP)
        val host = controller.start().resume().get()
        ApplicationLifecycle.onActivityResumed(host)
        harness.settle()
        assertEquals(listOf(token(intent)), startedSheets(host).map(::token))
        ReceptionChat.close()
    }

    @Test
    fun aLauncherReturnBeforeOrAfterConfigureGivesOneSheet() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val controller = TestActivities.build(ComponentActivity::class.java).create()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ReturningSheet.newIntent(controller.get(), launcher)
        configure()
        ReturningSheet.newIntent(controller.get(), launcher)
        val host = controller.start().resume().get()
        ApplicationLifecycle.onActivityResumed(host)
        harness.settle()
        assertEquals(listOf(token(intent)), startedSheets(host).map(::token))
        ReceptionChat.close()
    }

    /** A deep link (or a shortcut, a widget) reaches the host while the sheet waits: the app goes there, no chat. */
    @Test
    fun aNewIntentBeforeTheRestoreCancelsIt() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        Reception.noteNewIntent(host, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("rn://explore")))
        configure()
        assertEquals(emptyList<Intent>(), startedSheets(host))
        assertFalse(ChatPresenter.isPresented)
        assertNull(recordedToken())
    }

    /** The return through the app icon is the person's return to the chat, as in [ReturningSheet]. */
    @Test
    fun aLauncherIntentBeforeTheRestoreKeepsIt() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        Reception.noteNewIntent(host, launcher)
        configure()
        assertEquals(listOf(token(intent)), startedSheets(host).map(::token))
        ReceptionChat.close()
    }

    /**
     * A tap on the SDK's notification whose trampoline starts the app's launch intent into the host: the restore is
     * cancelled, and the tap opens the chat through its own path, one sheet.
     */
    @Test
    fun aNotificationTapsNewIntentGivesOneSheet() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        ReceptionPush.notificationTapped("tap", "conversation")
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Reception.noteNewIntent(host, launch)
        configure()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        harness.settle()
        val sheets = startedSheets(host)
        assertEquals(1, sheets.size)
        assertEquals(token(sheets.single()), recordedToken())
        ReceptionChat.close()
    }

    @Test
    fun routingTurnedOffBeforeConfigureKeepsTheRestore() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        val host = resumedHost()
        ReceptionPush.setRoutingEnabled(false)
        try {
            configure()
            assertEquals(listOf(token(intent)), startedSheets(host).map(::token))
        } finally {
            ReceptionPush.setRoutingEnabled(true)
        }
        ReceptionChat.close()
    }

    @Test
    fun aSecondProcessDeathDuringTheWaitRestoresNothing() {
        val intent = openTheSheet()
        restoredBeforeConfigure(intent)
        // The finished sheet is no longer in the task, so the next process recreates only the host.
        processDied()
        val host = resumedHost()
        configure()
        assertEquals(emptyList<Intent>(), startedSheets(host))
        assertFalse(ChatPresenter.isPresented)
    }

    /** "Don't keep activities": the sheet is recreated in the same, configured process and kept with its state. */
    @Test
    fun aSheetRecreatedInAConfiguredProcessIsKept() {
        val intent = openTheSheet()
        val sheet = TestActivities.build(ReceptionChatActivity::class.java, intent).create(Bundle()).get()
        assertFalse(sheet.isFinishing)
        assertTrue(ChatPresenter.isPresented)
        ReceptionChat.close()
    }
}
