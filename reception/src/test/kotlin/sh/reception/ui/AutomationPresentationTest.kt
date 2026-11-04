package sh.reception.ui

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import sh.reception.Reception
import sh.reception.ReceptionState
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.Automations
import sh.reception.client.Storage
import sh.reception.viewmodel.ChatHarness
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** An automation's chat opens through the real presenter, which saves the presentation record before the sheet starts. */
@RunWith(RobolectricTestRunner::class)
class AutomationPresentationTest {
    private val harness = ChatHarness()
    private val automations = Automations()
    private var finished = false

    @Before
    fun setUp() {
        harness.start()
    }

    @After
    fun tearDown() {
        ChatPresenter.dismiss()
        harness.stop()
    }

    private fun resumed(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        .also { ApplicationLifecycle.onActivityResumed(it) }

    /** Starts the open rule now, as `track` does once the service answered; the storage thread is held meanwhile. */
    private fun openWhileStorageIsHeld(): () -> Unit {
        val release = CountDownLatch(1)
        val held = Storage.launch { release.await(5, TimeUnit.SECONDS) }
        val start = SystemClock.elapsedRealtime()
        automations.scope.launch {
            automations.openChatWhenSettled(start, ReceptionState.presentationRevision, arriving = true)
            finished = true
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the presentation waits for its record", ChatPresenter.isPresented)
        return {
            release.countDown()
            runBlocking { held.join() }
            harness.settle()
        }
    }

    @Test
    fun aPresentationCancelledByAnotherActivityIsTriedAgainOverItWithinTheWindow() {
        val first = resumed()
        val release = openWhileStorageIsHeld()
        // Another activity resumed while the record was saved: the first presentation is cancelled.
        val second = resumed()
        release()
        assertNull(shadowOf(first).nextStartedActivity)
        assertFalse("the open rule gave up after the cancellation", finished)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        harness.settle()
        assertEquals(ReceptionChatActivity::class.java.name, shadowOf(second).nextStartedActivity?.component?.className)
        assertTrue(finished)
        assertTrue(automations.takeOpenedChat())
    }

    @Test
    fun aChatTheHostClosedWhileTheRecordIsSavedIsNotOpenedAgain() {
        val host = resumed()
        val release = openWhileStorageIsHeld()
        Reception.closeChat()
        release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        harness.settle()
        assertNull("the automation reopened the chat the host closed", shadowOf(host).nextStartedActivity)
        assertFalse(ChatPresenter.isPresented)
        assertTrue(finished)
        assertFalse(automations.takeOpenedChat())
    }

    @Test
    fun aRecordSavedAfterTheWindowEndedStartsNoSheet() {
        val host = resumed()
        val release = openWhileStorageIsHeld()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Automations.OPEN_WINDOW_MILLIS + 100))
        release()
        assertNull("sheet started after the window", shadowOf(host).nextStartedActivity)
        assertFalse(ChatPresenter.isPresented)
        // The open rule looks again after its poll interval and finds the window over.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(finished)
        assertFalse(automations.takeOpenedChat())
    }
}
