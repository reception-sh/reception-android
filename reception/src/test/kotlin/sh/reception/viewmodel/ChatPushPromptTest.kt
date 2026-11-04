package sh.reception.viewmodel

import android.Manifest
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.derivedStateOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.reception.client.NotificationAuthorization
import sh.reception.client.pushActive
import sh.reception.model.Message

/** The notification card's answer: only a grant or a denial is kept; a dismissed dialog asks again after a relaunch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatPushPromptTest {
    private val harness = ChatHarness()

    @Before
    fun notificationsOff() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
    }

    @After
    fun tearDown() = harness.stop()

    private fun authorization(chat: ChatModel, activity: ComponentActivity): NotificationAuthorization? {
        chat.notificationAuthorization = null
        chat.refreshNotificationAuthorization(activity, canRequest = true)
        harness.mainLoopUntil { chat.notificationAuthorization != null }
        return chat.notificationAuthorization
    }

    @Test
    fun aDismissedDialogHidesTheCardOnlyUntilTheNextLaunch() {
        val chat = harness.start()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertEquals(NotificationAuthorization.NOT_DETERMINED, authorization(chat, activity))
        chat.continuePushPrompt {}
        chat.notificationPermissionAnswered(granted = false, activity)
        harness.mainLoopUntil { true }
        assertTrue(chat.pushPromptAnswered)
        assertFalse(harness.session.store.notificationPermissionAnswered)

        val relaunched = ChatModel(harness.session)
        harness.mainLoopUntil { relaunched.isRestored }
        assertFalse(relaunched.pushPromptAnswered)
        assertEquals(NotificationAuthorization.NOT_DETERMINED, authorization(relaunched, activity))
        relaunched.stop()
    }

    @Test
    fun aDenialIsKept() {
        val chat = harness.start()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(RuntimeEnvironment.getApplication().packageManager)
            .setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        chat.continuePushPrompt {}
        chat.notificationPermissionAnswered(granted = false, activity)
        harness.mainLoopUntil { harness.session.store.notificationPermissionAnswered }
        assertEquals(NotificationAuthorization.DENIED, authorization(chat, activity))
    }

    @Test
    fun theStatusLineFollowsALaterServerAnswer() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val chat = harness.start()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertEquals(NotificationAuthorization.AUTHORIZED, authorization(chat, activity))
        chat.messages = listOf(Message(id = "m1", sender = "USER", text = "Hi", attachments = emptyList(), createdAt = 0))
        harness.session.store.pushActive = false
        val slot = derivedStateOf { chat.pushPromptSlot }
        assertEquals(PushPromptSlot.None, slot.value)
        // An unread answer or a device update writes the store; the slot must notice without any other change.
        harness.session.store.pushActive = true
        assertEquals(PushPromptSlot.Status, slot.value)
    }
}
