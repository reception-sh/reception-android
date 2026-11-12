package sh.reception.viewmodel

import sh.reception.Log
import sh.reception.ReceptionIdentity
import sh.reception.model.ConversationResponse

internal fun ChatModel.disableSending() {
    if (!sendingDisabled) Log.info { "Device blocked by the team" }
    sendingPermissionRevision += 1
    sendingDisabled = true
    cancelAutomaticRetry()
}

internal fun ChatModel.requireVerification() {
    if (!verificationRequired) Log.info { "Sending requires a verified user" }
    sendingPermissionRevision += 1
    verificationRequired = true
}

/** Local knowledge wins over responses that were already underway, such as after `identifyWithToken`. */
internal fun ChatModel.clearVerificationRequirement() {
    sendingPermissionRevision += 1
    verificationRequired = false
}

internal fun ChatModel.updateSendingPermission(device: ConversationResponse.Device, revision: Int) {
    // A GET started before a rejected POST must not re-enable sending with stale data.
    if (revision != sendingPermissionRevision) return
    device.blocked?.let { blocked -> if (blocked) disableSending() else sendingDisabled = false }
    device.verificationRequired?.let { required ->
        // A staged token that has not reached the service yet may still lift the requirement.
        val tokenPending = ReceptionIdentity.token?.let { !it.sent } ?: false
        if (!required) verificationRequired = false else if (!tokenPending) requireVerification()
    }
}

/** A send that found the device blocked before posting goes back into the composer, text and photos. */
internal fun ChatModel.restoreBlockedMessage(id: String) {
    val message = messages.firstOrNull { it.id == id } ?: return
    clearPending(id)
    session?.retries?.remove(id)
    composer.restoreBlocked(message.text, message.images)
    messages = messages.filterNot { it.id == id }
    saveMessages()
}
