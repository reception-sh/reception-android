package sh.reception.client

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.reception.R
import sh.reception.ui.ReceptionNotificationActivity

/** Which FCM messages are Reception's, the badge count a reply shows, and which notifications reading removes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushNotificationsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun grant() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val launcher = ComponentName(context.packageName, "Launcher")
        val packages = shadowOf(context.packageManager)
        packages.addActivityIfNotPresent(launcher)
        packages.addIntentFilterForActivity(launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    @Test
    fun onlyAPayloadWithAConversationIsReceptions() {
        val payload = PushNotifications.payload(mapOf("reception" to "c1", "title" to "Team", "body" to "Hi", "badge" to "3",
            "sound" to "default"))
        assertEquals("c1", payload?.conversationId)
        assertEquals("Team", payload?.title)
        assertEquals(3, payload?.badge)
        assertTrue(payload?.sound == true)
        val quiet = PushNotifications.payload(mapOf("reception" to "c1", "title" to "T", "body" to "B"))
        assertNull(quiet?.badge)
        assertFalse(quiet?.sound == true)
        assertNull(PushNotifications.payload(mapOf("reception" to "")))
        assertNull(PushNotifications.payload(mapOf("google.message_id" to "x", "title" to "Other app")))
        assertNull(PushNotifications.payload(emptyMap()))
        assertNull(PushNotifications.payload(mapOf("reception" to "c1", "badge" to "-1"))?.badge)
    }

    @Test
    fun theBadgeShowsOnlyWhileTheSdkUpdatesIt() {
        val payload = requireNotNull(PushNotifications.payload(mapOf("reception" to "c1", "badge" to "2")))
        assertEquals(2, PushNotifications.number(payload, updatesAppBadge = true))
        assertNull(PushNotifications.number(payload, updatesAppBadge = false))
        val zero = requireNotNull(PushNotifications.payload(mapOf("reception" to "c1", "badge" to "0")))
        assertNull(PushNotifications.number(zero, updatesAppBadge = true))
    }

    @Test
    fun aPostedReplyCarriesItsCountAndTapsGoToTheSdksActivity() {
        val payload = requireNotNull(PushNotifications.payload(mapOf("reception" to "c1", "title" to "Team", "body" to "Hi")))
        assertTrue(PushNotifications.post(context, payload, "n1", updatesAppBadge = true, number = 4, color = null,
            smallIcon = R.drawable.reception_symbol_notifications))
        val posted = shadowOf(manager).getNotification(PushNotifications.tag("c1"), PushNotifications.ID)
        assertEquals(4, posted.number)
        assertEquals("c1", posted.group)
        assertEquals(PushNotifications.CHANNEL, posted.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(PushNotifications.CHANNEL).importance)
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(ReceptionNotificationActivity::class.java.name, intent.component?.className)
        assertEquals("c1", intent.getStringExtra(PushNotifications.EXTRA_CONVERSATION))
        assertEquals("n1", intent.getStringExtra(PushNotifications.EXTRA_NOTIFICATION))
    }

    @Test
    fun aHostThatKeepsTheBadgePostsToAChannelWithoutBadges() {
        val payload = requireNotNull(PushNotifications.payload(mapOf("reception" to "c1", "title" to "Team", "body" to "Hi")))
        assertTrue(PushNotifications.post(context, payload, "n1", updatesAppBadge = false, number = null, color = null,
            smallIcon = R.drawable.reception_symbol_notifications))
        val posted = shadowOf(manager).getNotification(PushNotifications.tag("c1"), PushNotifications.ID)
        assertEquals(PushNotifications.UNBADGED_CHANNEL, posted.channelId)
        val channel = manager.getNotificationChannel(PushNotifications.UNBADGED_CHANNEL)
        assertFalse(channel.canShowBadge())
        assertNull(manager.getNotificationChannel(PushNotifications.CHANNEL))
        PushNotifications.post(context, payload, "n2", updatesAppBadge = true, number = 1, color = null,
            smallIcon = R.drawable.reception_symbol_notifications)
        assertTrue(manager.getNotificationChannel(PushNotifications.CHANNEL).canShowBadge())
        assertEquals(manager.getNotificationChannel(PushNotifications.CHANNEL).name, channel.name)
    }

    @Test
    fun conversationsWhoseIdsShareAHashKeepTheirOwnNotificationAndTap() {
        val ids = listOf("c0000000000000000000000an", "c0000000000000000000000c0")
        assertEquals(ids[0].hashCode(), ids[1].hashCode())
        for (id in ids) {
            val payload = requireNotNull(PushNotifications.payload(mapOf("reception" to id, "title" to "Team", "body" to id)))
            assertTrue(PushNotifications.post(context, payload, "n-$id", updatesAppBadge = true, number = null, color = null,
                smallIcon = R.drawable.reception_symbol_notifications))
        }
        assertEquals(2, manager.activeNotifications.size)
        for (id in ids) {
            val posted = shadowOf(manager).getNotification(PushNotifications.tag(id), PushNotifications.ID)
            val intent = shadowOf(posted.contentIntent).savedIntent
            assertEquals(id, intent.getStringExtra(PushNotifications.EXTRA_CONVERSATION))
            assertEquals("n-$id", intent.getStringExtra(PushNotifications.EXTRA_NOTIFICATION))
        }
        val first = shadowOf(manager).getNotification(PushNotifications.tag(ids[0]), PushNotifications.ID).contentIntent
        val second = shadowOf(manager).getNotification(PushNotifications.tag(ids[1]), PushNotifications.ID).contentIntent
        assertNotEquals(first, second)
    }

    @Test
    fun readingRemovesOnlyTheSdksNotifications() {
        val payload = requireNotNull(PushNotifications.payload(mapOf("reception" to "c1", "title" to "Team", "body" to "Hi")))
        PushNotifications.post(context, payload, "n1", updatesAppBadge = true, number = null, color = null,
            smallIcon = R.drawable.reception_symbol_notifications)
        val own = Notification.Builder(context, "app").setSmallIcon(R.drawable.reception_symbol_notifications).build()
        manager.notify("app", 7, own)
        assertEquals(2, manager.activeNotifications.size)
        PushNotifications.cancelAll(context)
        assertEquals(listOf("app"), manager.activeNotifications.map { it.tag })
    }
}
