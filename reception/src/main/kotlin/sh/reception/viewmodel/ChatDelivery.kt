package sh.reception.viewmodel

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import sh.reception.Log
import sh.reception.ReceptionEvent
import sh.reception.ReceptionState
import sh.reception.ReceptionUnread
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.ImageUploader
import sh.reception.client.ReceptionAPI
import sh.reception.client.ReceptionAPIError
import sh.reception.client.RequestContext
import sh.reception.client.RequestProbe
import sh.reception.client.RetryScope
import sh.reception.client.RetryState
import sh.reception.client.admit
import sh.reception.client.hasConversation
import sh.reception.client.hasStartedChat
import sh.reception.client.isRegistered
import sh.reception.client.update
import sh.reception.model.Message
import sh.reception.model.MessageStatus
import sh.reception.model.SendResponse
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

internal const val MAXIMUM_TEXT_LENGTH = 4_000
internal const val MAXIMUM_PHOTOS = 5

@Serializable
private class SendInput(val clientId: String, val text: String, val attachmentIds: List<String>)

/** Optimistic send: the message shows at once under a new client UUID, which every retry keeps. */
internal fun ChatModel.send(text: String, images: List<ByteArray>) {
    val trimmed = text.trim()
    if (!canSend || !cooldownAllowsSend(photos = images.isNotEmpty()) || (trimmed.isEmpty() && images.isEmpty()) ||
        trimmed.length > MAXIMUM_TEXT_LENGTH || images.size > MAXIMUM_PHOTOS
    ) return
    val message = Message(id = UUID.randomUUID().toString().uppercase(), sender = "USER", text = trimmed,
        attachments = emptyList(), createdAt = System.currentTimeMillis(), status = MessageStatus.SENDING, images = images)
    messages = messages + message
    session?.store?.hasStartedChat = true
    saveMessages()
    submit(message.id, userInitiated = true)
    if (isChatActive) startPolling()
    ReceptionState.unreadMonitor.setActive(ApplicationLifecycle.isActive)
}

internal fun ChatModel.retry(messageId: String) {
    val message = messages.firstOrNull { it.id == messageId } ?: return
    if (!canSend || message.status != MessageStatus.FAILED || cooldown(message) != null) return
    if (automaticRetry?.messageId == messageId) cancelAutomaticRetry()
    update(messageId) {
        it.copy(status = MessageStatus.SENDING, awaitsManualRetry = false, retryAfterLimitRelease = false,
            retryAfterTransientFailure = false)
    }
    saveMessages()
    session?.retries?.set(messageId, RetryState())
    submit(messageId, userInitiated = true)
}

/** A user action may pass a halted session once (the probe); automatic resends may not. */
internal fun ChatModel.submit(id: String, userInitiated: Boolean = false) {
    if (!canSend || sends[id] != null || (!userInitiated && session?.halt != null)) return
    if (userInitiated) session?.retries?.set(id, RetryState())
    schedulePending(id)
    lateinit var job: Job
    job = scope.launch(if (userInitiated) RequestContext(probe = RequestProbe()) else EmptyCoroutineContext) {
        try {
            deliver(id)
        } finally {
            clearPending(id)
            if (sends[id] === job) sends.remove(id)
        }
        retryFailedAutomatically()
    }
    sends[id] = job
}

private suspend fun ChatModel.deliver(id: String) {
    val session = session ?: return
    if (!current) return
    val draft = messages.firstOrNull { it.id == id && it.seq == null } ?: return
    var postingMessage = false
    val scopes = cooldownScopes(draft)
    try {
        if (draft.images.size < draft.attachmentIds.size || draft.images.size != (draft.expectedImageCount ?: draft.images.size)) {
            throw ReceptionAPIError("invalid_image", 0)
        }
        // A known wait holds the whole send, so it spends neither request nor upload budget.
        session.admit(scopes)
        // This cached submission already carries the user's send intent; drafts never grant registration.
        if (draft.retryAfterTransientFailure && isWatched) session.registrationAllowed = true
        // With a credential, probe the requested send itself: a successful PATCH cannot prove an unblock.
        if (session.halt == null || !session.isRegistered) session.update()
        var attachments = draft.attachmentIds
        for (index in attachments.size until draft.images.size) {
            session.admit(scopes)
            val count = draft.images.size.toDouble()
            uploadProgress = index / count
            val attachment = try {
                ImageUploader.upload(draft.images[index], id, index, session) { fraction ->
                    uploadProgress = (index + fraction) / count
                }
            } catch (error: ReceptionAPIError) {
                if (!error.isCooldown) Log.error { "Image upload failed, HTTP ${error.status} ${error.code}" }
                throw error
            }
            if (!current) return
            attachments = attachments + attachment
            val uploaded = attachments
            update(id) { it.copy(attachmentIds = uploaded) }
            saveMessages()
            uploadProgress = attachments.size / count
        }
        if (!canSend) return restoreBlockedMessage(id)
        postingMessage = true
        val response = session.api.request<SendResponse>("conversation/messages", "POST", authenticated = true,
            body = ReceptionAPI.encode(SendInput(id, draft.text, attachments)), photos = attachments.isNotEmpty())
        if (!current) return
        ReceptionUnread.invalidateRead()
        session.store.hasConversation = true
        merge(listOf(response.message.let { it.copy(clientId = it.clientId ?: id) }), advancesCursor = false)
        if (verificationRequired) clearVerificationRequirement()
        if (isChatActive) startPolling()
        ReceptionState.onEvent?.invoke(ReceptionEvent.MessageSent)
        if (draft.images.isNotEmpty()) ReceptionState.onEvent?.invoke(ReceptionEvent.ImageSent)
        session.retries.remove(id)
        limitReleaseRetries.remove(id)
        Log.debug { "Message sent" }
    } catch (error: ReceptionAPIError) {
        failed(id, error, postingMessage)
    } finally {
        uploadProgress = null
    }
}

private fun ChatModel.failed(id: String, error: ReceptionAPIError, postingMessage: Boolean) {
    val session = session ?: return
    if (handleReset(error)) return
    if (!current || messages.none { it.id == id && it.seq == null }) return
    if (postingMessage && ((error.status == 400 && error.code == "invalid_attachment") ||
            (error.status == 409 && error.code == "upload_incomplete"))
    ) {
        // Only a rejected message proves that the saved upload mapping cannot be reused.
        update(id) { it.copy(attachmentIds = emptyList()) }
    }
    if (error.status == 403 && error.code == "blocked") disableSending()
    if (error.status == 403 && error.code == "verification_required") requireVerification()
    when {
        error.isCooldown -> Log.info { "Message send held, ${error.retryScope?.raw ?: "unknown"} paused" }
        error.status > 0 -> Log.error { "Message send failed, HTTP ${error.status} ${error.code}" }
        else -> {
            Log.error { "Message send failed, network error" }
            error.networkError?.let { Log.debug { "Message send failed, network error, $it" } }
        }
    }
    update(id) { message ->
        val failed = message.copy(status = MessageStatus.FAILED,
            retryAfterTransientFailure = isRecoverableSubmissionFailure(error))
        val cooldown = cooldown(failed)
        val limitHeld = error.retryScope != null && error.retryAfter != null &&
            session.cooldowns.blocking(cooldownScopes(failed).filter { it == RetryScope.MESSAGES || it == RetryScope.UPLOADS }) != null
        // Only a short wait in a watched chat resends by itself; any other wait needs the person again.
        val manual = cooldown != null && !(cooldown.automatic && isWatched)
        failed.copy(heldBy = cooldown?.scope, retryAfterLimitRelease = limitHeld,
            awaitsManualRetry = failed.awaitsManualRetry || manual)
    }
    if (id in limitReleaseRetries) pauseLimitReleaseRetries()
    saveMessages()
    if (session.halt == null) session.retries.getOrPut(id) { RetryState() }.failed(error)
    report(error)
}
