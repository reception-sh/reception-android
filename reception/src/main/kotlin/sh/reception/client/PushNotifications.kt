package sh.reception.client

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import sh.reception.Log
import sh.reception.ReceptionLocalization
import sh.reception.strings.Strings
import sh.reception.ui.ReceptionNotificationActivity

/**
 * The notifications the SDK posts for replies (SPEC.md, Push notifications): one per conversation on the
 * `reception_replies` channel (`reception_replies_unbadged` while the app keeps the badge), tagged `reception:<conversation>`;
 * a tap goes to [ReceptionNotificationActivity] with the conversation.
 */
internal object PushNotifications {
    const val CHANNEL: String = "reception_replies"

    /** A channel's badge setting cannot change once it exists, so a host that keeps the badge gets its own channel. */
    const val UNBADGED_CHANNEL: String = "reception_replies_unbadged"
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

    /** The channel a reply posts to: launchers show a dot or count only while the SDK updates the badge. */
    fun channel(updatesAppBadge: Boolean): String = if (updatesAppBadge) CHANNEL else UNBADGED_CHANNEL

    /**
     * One notification per conversation; a newer reply replaces it. Android keys a notification by tag and id; ids alone
     * collide (two conversation ids can share a hash), so the tag carries the conversation and the id is fixed.
     */
    fun tag(conversationId: String): String = "$TAG:$conversationId"

    const val ID: Int = 1

    /** The tap's intent data: PendingIntents are equal when their intents are, extras aside, so each conversation needs its own. */
    fun uri(conversationId: String): Uri = Uri.Builder().scheme(TAG).authority("conversation").appendPath(conversationId).build()

    /**
     * Posts [payload]; [updatesAppBadge] picks the channel, [number] is the badge count to show, [color] the accent.
     * Returns false when nothing was shown.
     */
    fun post(
        context: Context,
        payload: Payload,
        notificationId: String,
        updatesAppBadge: Boolean,
        number: Int?,
        color: Int?,
        smallIcon: Int,
    ): Boolean {
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
        if (context.packageManager.getLaunchIntentForPackage(context.packageName) == null) {
            return false.also { Log.error { "Notification not shown, the app has no launcher activity" } }
        }
        val tap = Intent(context, ReceptionNotificationActivity::class.java)
            .setData(uri(payload.conversationId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_CONVERSATION, payload.conversationId)
            .putExtra(EXTRA_NOTIFICATION, notificationId)
        val content = PendingIntent.getActivity(context, 0, tap,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val channel = channel(updatesAppBadge)
        // Created on every post, so the channel's name follows the app's language, also before configure.
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(channel, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(ReceptionLocalization.string(ReceptionLocalization.language(context), Strings.supportReplies))
                .setShowBadge(updatesAppBadge)
                .build(),
        )
        val builder = NotificationCompat.Builder(context, channel)
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
            manager.notify(tag(payload.conversationId), ID, builder.build())
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
        delivered.filter { it.tag?.startsWith("$TAG:") == true && it.packageName == context.packageName }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
