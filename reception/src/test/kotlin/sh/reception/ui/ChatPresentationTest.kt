package sh.reception.ui

import android.app.Activity
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import sh.reception.ReceptionState
import sh.reception.client.ApplicationLifecycle
import sh.reception.viewmodel.ChatHarness

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
        return shadowOf(host).nextStartedActivity
    }

    private fun created(intent: Intent?): ReceptionChatActivity =
        Robolectric.buildActivity(ReceptionChatActivity::class.java, requireNotNull(intent)).create().get()

    @Test
    fun aSheetClosedInTheSameCallbackAsItsOpeningFinishesWhenCreated() {
        val intent = launch {
            ChatPresenter.open()
            ChatPresenter.close()
        }
        assertTrue(created(intent).isFinishing)
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun anOpenedSheetIsPresented() {
        val sheet = created(launch { ChatPresenter.open() })
        assertFalse(sheet.isFinishing)
        assertTrue(ChatPresenter.isPresented)
        ChatPresenter.close()
    }

    @Test
    fun aLogoutDuringTheLaunchFinishesTheQueuedSheet() {
        val intent = launch {
            ChatPresenter.open()
            ReceptionState.resetLocal()
        }
        assertTrue(created(intent).isFinishing)
    }

    @Test
    fun aSheetWithoutAPresentationFinishes() {
        launch {}
        assertTrue(created(Intent(RuntimeEnvironment.getApplication(), ReceptionChatActivity::class.java)).isFinishing)
    }
}
