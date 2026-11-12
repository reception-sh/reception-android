package sh.reception

import android.app.NotificationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.reception.testing.MainLooperDrain
import sh.reception.viewmodel.ChatHarness

/**
 * A token from the SDK's own messaging service (or a plugin's) is staged only while notifications are allowed, as
 * `registerForPushIfAuthorized` does; one the host hands to `setPushToken` is the host's decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ReceptionInternalApi::class)
class PushTokenGateTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()
    private val application = RuntimeEnvironment.getApplication()
    private val token = "fcm:token_1"

    @After
    fun tearDown() = harness.stop()

    private fun notificationsAllowed(allowed: Boolean) =
        shadowOf(application.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(allowed)

    private val staged: String? get() = ReceptionState.session?.pending?.pushToken

    @Test
    fun theServiceStagesNoTokenWhileNotificationsAreNotAllowed() {
        harness.start()
        notificationsAllowed(false)
        ReceptionMessagingService().onNewToken(token)
        assertNull(staged)
    }

    @Test
    fun theServiceStagesATokenWhileNotificationsAreAllowed() {
        harness.start()
        notificationsAllowed(true)
        ReceptionMessagingService().onNewToken(token)
        assertEquals(token, staged)
    }

    @Test
    fun thePluginHookFollowsTheSameRule() {
        harness.start()
        notificationsAllowed(false)
        Reception.registerPushTokenIfAuthorized(token)
        assertNull(staged)
        notificationsAllowed(true)
        Reception.registerPushTokenIfAuthorized(token)
        assertEquals(token, staged)
    }

    @Test
    fun theHostsOwnTokenIsStagedWhateverThePermission() {
        harness.start()
        notificationsAllowed(false)
        Reception.setPushToken(token)
        assertEquals(token, staged)
    }

    /**
     * Android 10 at startup: the service's `onNewToken` and the configure-time registration stage the same token 2 ms
     * apart. The second must not send a device update of its own (it joined the first and sent it again).
     */
    @Test
    fun theSameTokenStagedTwiceSendsOneDeviceUpdate() {
        harness.start()
        notificationsAllowed(true)
        // The startup's own device update and unread refresh.
        refreshed(after = 0)
        val before = updates()
        ReceptionMessagingService().onNewToken(token)
        Reception.registerPushTokenIfAuthorized(token)
        refreshed(after = 1)
        assertEquals(token, staged)
        assertEquals(1, updates() - before)
        // A later delivery of the same token sends nothing either.
        ReceptionMessagingService().onNewToken(token)
        harness.settle()
        assertNull(ReceptionUnread.runningRefresh)
        assertEquals(1, updates() - before)
    }

    private fun updates() = harness.requests("PATCH", "/v1/devices/me").size

    /** Waits until the unread refresh that follows [after] earlier ones has finished. */
    private fun refreshed(after: Int) {
        harness.mainLoopUntil {
            harness.requests("GET", "/v1/conversation/unread").size > after && ReceptionUnread.runningRefresh == null
        }
        harness.settle()
    }
}
