package sh.reception

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * A ready-made messaging service for apps without a `FirebaseMessagingService` of their own: it shows Reception's
 * replies and hands new tokens to Reception. The SDK declares it disabled for Firebase; enable it in your manifest with
 * the `com.google.firebase.MESSAGING_EVENT` intent filter (see the setup guide). Apps with their own service call
 * [Reception.handlePushMessage] and [Reception.setPushToken] from it instead.
 */
public class ReceptionMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        Reception.handlePushMessage(this, message.data, message.messageId)
    }

    override fun onNewToken(token: String) {
        MainThread.run { if (ReceptionState.session != null) ReceptionAccount.setPushToken(token) }
    }
}
