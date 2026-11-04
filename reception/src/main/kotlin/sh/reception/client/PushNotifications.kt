package sh.reception.client

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import sh.reception.Log
import sh.reception.ReceptionLocalization
import sh.reception.strings.Strings

/**
 * The notifications the SDK posts for replies (SPEC.md, Push notifications): one per conversation on the
 * `reception_replies` channel, tagged `reception`, opening the app's launcher activity with the conversation.
 */
internal object PushNotifications {
    const val CHANNEL: String = "reception_replies"
    const val TAG: String = "reception"
    const val EXTRA_CONVERSATION: String = "sh.reception.conversationId"
    const val EXTRA_NOTIFICATION: String = "sh.reception.notificationId"

    /** `brand.pushPayloadKey` of the service. */
    const val PAYLOAD_KEY: String = "reception"

    /** The FCM data fields of a reply; [badge] is absent when the device does not update the badge. */
    class Payload(val conversationId: String, val title: String, val body: String, val badge: Int?, val sound: Boolean)

    /** A Reception payload, or null for another app's or Firebase's message. */
    fun payload(data: Map<String, String>): Payload? {
        val id = data[PAYLOAD_KEY]?.takeIf { it.isNotEmpty() } ?: return null
        return Payload(id, data["title"].orEmpty(), data["body"].orEmpty(), data["badge"]?.toIntOrNull()?.takeIf { it >= 0 },
            sound = data["sound"] != null)
    }

    /** The count a reply shows: the payload's badge, only while the SDK updates the badge, and never zero. */
    fun number(payload: Payload, updatesAppBadge: Boolean): Int? =
        if (updatesAppBadge) payload.badge?.takeIf { it > 0 } else null

    /** One notification per conversation; a newer reply replaces it. */
    fun id(conversationId: String): Int = conversationId.hashCode()

    /** Posts [payload]; [number] is the badge count to show, [color] the accent. Returns false when nothing was shown. */
    fun post(context: Context, payload: Payload, notificationId: String, number: Int?, color: Int?, smallIcon: Int): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.debug { "Notification not shown, permission not granted" }
            return false
        }
        if (!manager.areNotificationsEnabled()) {
            Log.debug { "Notification not shown, notifications disabled" }
            return false
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return false.also { Log.error { "Notification not shown, the app has no launcher activity" } }
        // A running app gets the tap in its launcher activity (onNewIntent) instead of a second instance.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CONVERSATION, payload.conversationId)
            .putExtra(EXTRA_NOTIFICATION, notificationId)
        val content = PendingIntent.getActivity(context, id(payload.conversationId), launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Created on every post, so the channel's name follows the app's language.
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(ReceptionLocalization.string(Strings.supportReplies))
                .setShowBadge(true)
                .build(),
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(smallIcon)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(payload.conversationId)
            .setAutoCancel(true)
            .setContentIntent(content)
            .setSilent(!payload.sound)
        if (payload.sound) builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        color?.let { builder.setColor(it) }
        number?.let { builder.setNumber(it) }
        return try {
            manager.notify(TAG, id(payload.conversationId), builder.build())
            Log.debug { "Notification shown, conversation ${payload.conversationId}" }
            true
        } catch (_: SecurityException) {
            Log.debug { "Notification not shown, permission not granted" }
            false
        }
    }

    /** Removes the SDK's delivered notifications; the app's own stay. */
    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val delivered = try {
            manager.activeNotifications
        } catch (_: SecurityException) {
            return
        }
        delivered.filter { it.tag == TAG && it.packageName == context.packageName }.forEach { manager.cancel(TAG, it.id) }
    }
}
