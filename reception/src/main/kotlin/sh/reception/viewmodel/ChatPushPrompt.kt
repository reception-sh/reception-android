package sh.reception.viewmodel

import android.app.Activity
import kotlinx.coroutines.launch
import sh.reception.Log
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionPush
import sh.reception.client.NotificationPermission
import sh.reception.client.pushActive
import sh.reception.model.MessageStatus

// The notification card and status line under the person's latest message (SDK_SPEC.md, notification card).

internal val ChatModel.pushPromptSlot: PushPromptSlot
    get() {
        val prompt = if (ReceptionAppearance.remoteEnabled) ReceptionAppearance.configuration.activePushPrompt else null
        val store = session?.takeIf { it.isPrepared }?.store
        val last = messages.lastOrNull()
        return PushPromptDecision.resolve(prompt, notificationAuthorization, declined = store?.pushPromptDeclined == true,
            answered = pushPromptAnswered, lastSender = last?.sender, lastFailed = last?.status == MessageStatus.FAILED,
            canWrite = !sendingDisabled && !verificationRequired, pushActive = store?.pushActive,
            hidesStatus = ReceptionAppearance.resolved.hidesPushStatus)
    }

/** Reads the authorization once the stored answer is loaded; when the chat appears and on every foreground. */
internal fun ChatModel.refreshNotificationAuthorization(activity: Activity?, canRequest: Boolean) {
    scope.launch {
        restored()
        val session = session ?: return@launch
        if (!current || !session.isPrepared) return@launch
        notificationAuthorization = NotificationPermission.read(session.store.context, activity, canRequest,
            answered = session.store.notificationPermissionAnswered)
    }
}

/** Continue: hides the card at once, then [request] shows the system's permission dialog. */
internal fun ChatModel.continuePushPrompt(request: () -> Unit) {
    if (pushPromptAnswered) return
    pushPromptAnswered = true
    request()
}

/**
 * The system dialog closed. A grant or a denial is kept, so the card never asks again, and a granted device registers.
 * A dismissal without a choice is not an answer: the card stays hidden while this chat model lives and asks again after
 * the next launch.
 */
internal fun ChatModel.notificationPermissionAnswered(granted: Boolean, activity: Activity?) {
    val session = session ?: return
    val established = NotificationPermission.isEstablished(session.store.context, activity, granted)
    if (established) session.afterPrepared { session.store.notificationPermissionAnswered = true }
    Log.debug {
        when {
            granted -> "Notification prompt answered, granted"
            established -> "Notification prompt answered, denied"
            else -> "Notification prompt dismissed"
        }
    }
    refreshNotificationAuthorization(activity, canRequest = true)
    ReceptionPush.registerIfAuthorized()
}

internal fun ChatModel.declinePushPrompt() {
    session?.afterPrepared { session.store.pushPromptDeclined = true }
    // The stored flag is not Compose state; answering hides the card the same way.
    pushPromptAnswered = true
    Log.debug { "Notification prompt declined" }
}
