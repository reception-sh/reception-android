package sh.reception.viewmodel

import kotlinx.coroutines.launch
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.RetryScope
import sh.reception.client.ServerCooldowns
import sh.reception.model.Message
import sh.reception.model.MessageStatus

/** How a failed message can be sent again. */
internal sealed interface RetryAvailability {
    data object Now : RetryAvailability

    /** A short wait ends in an automatic resend while the chat stays visible; the person can cancel it. */
    data class Automatic(val until: Long) : RetryAvailability

    /** A cooldown holds the message; the notice above the composer explains the wait. */
    data object Waiting : RetryAvailability
}

private val textScopes = listOf(RetryScope.MESSAGES, RetryScope.REQUESTS, RetryScope.SESSION)

internal val ChatModel.cooldowns: ServerCooldowns?
    get() {
        // Read so that Compose redraws when a wait starts or ends.
        cooldownRevision
        return if (current) session?.cooldowns else null
    }

/** The chat is on screen in an active app, including while its first load is still running. */
internal val ChatModel.isWatched: Boolean
    get() = isVisible && ApplicationLifecycle.isActive

/** Everything a send of this message needs; photos also wait for the upload limits. */
internal fun cooldownScopes(message: Message): List<RetryScope> =
    if (message.images.isEmpty() && message.attachmentIds.isEmpty()) textScopes else textScopes + RetryScope.UPLOADS

internal fun ChatModel.cooldown(message: Message): ServerCooldowns.Cooldown? = cooldowns?.blocking(cooldownScopes(message))

/** The wait explained above the composer: whatever stops text first, then a photo-only wait. */
internal val ChatModel.composerCooldown: ServerCooldowns.Cooldown?
    get() = cooldowns?.blocking(textScopes) ?: cooldowns?.blocking(listOf(RetryScope.UPLOADS))

/** New sends also wait while held messages go out again, so nothing overtakes them. */
internal fun ChatModel.cooldownAllowsSend(photos: Boolean): Boolean =
    cooldowns?.blocking(if (photos) textScopes + RetryScope.UPLOADS else textScopes) == null && !resendingHeldMessages

private val ChatModel.resendingHeldMessages: Boolean
    get() = messages.any { message ->
        message.heldBy != null && !message.awaitsManualRetry && (message.status == MessageStatus.SENDING ||
            (retriesAutomatically(message) && cooldown(message) == null))
    }

internal val ChatModel.photosPaused: Boolean
    get() = cooldowns?.blocking(listOf(RetryScope.UPLOADS)) != null

internal val ChatModel.streamPaused: Boolean
    get() = cooldowns?.blocking(listOf(RetryScope.STREAM, RetryScope.REQUESTS, RetryScope.SESSION)) != null

internal fun ChatModel.retryAvailability(message: Message): RetryAvailability {
    if (message.status != MessageStatus.FAILED) return RetryAvailability.Now
    val cooldown = cooldown(message) ?: return RetryAvailability.Now
    return if (cooldown.automatic && retriesAutomatically(message)) RetryAvailability.Automatic(cooldown.until)
    else RetryAvailability.Waiting
}

/** Failed messages resend on their own unless the person must decide or the retry budget is spent. */
internal fun ChatModel.retriesAutomatically(message: Message): Boolean =
    message.status == MessageStatus.FAILED && !message.awaitsManualRetry && isWatched &&
        session?.retries?.get(message.id)?.let { !it.stopped } == true

/**
 * The person keeps the message and sends it again later with Retry. Held messages after it stop too, so none of
 * them overtakes it.
 */
internal fun ChatModel.cancelAutomaticRetry(messageId: String) {
    val start = messages.indexOfFirst { it.id == messageId }
    if (start < 0 || messages[start].status != MessageStatus.FAILED) return
    messages = messages.mapIndexed { index, message ->
        if (index < start || !(heldForAutomaticResend(message) || message.retryAfterLimitRelease ||
                message.retryAfterTransientFailure || index == start)
        ) return@mapIndexed message
        limitReleaseRetries.remove(message.id)
        message.copy(awaitsManualRetry = true, retryAfterLimitRelease = false, retryAfterTransientFailure = false)
    }
    saveMessages()
    // A resend of an earlier message continues; only a driver waiting on a canceled message stops.
    val target = automaticRetry?.messageId ?: return
    val targetIndex = messages.indexOfFirst { it.id == target }
    if (targetIndex in 0 until start) return
    cancelAutomaticRetry()
    retryFailedAutomatically()
}

private fun ChatModel.heldForAutomaticResend(message: Message): Boolean =
    message.status == MessageStatus.FAILED && !message.awaitsManualRetry &&
        (message.heldBy != null || cooldown(message) != null)

internal fun ChatModel.cancelAutomaticRetry() {
    automaticRetry?.task?.cancel()
    automaticRetry = null
}

/**
 * Short waits resend only while the person watches; closing the chat or leaving the app makes them manual, including
 * held messages whose wait just ended and that were still queued.
 */
internal fun ChatModel.deferCooldownRetries() {
    pauseLimitReleaseRetries()
    if (messages.none(::heldForAutomaticResend)) return
    messages = messages.map { if (heldForAutomaticResend(it)) it.copy(awaitsManualRetry = true) else it }
    saveMessages()
}

internal fun ChatModel.cooldownsChanged() {
    cooldownRevision += 1
    if (messages.any { it.id in limitReleaseRetries && cooldown(it) != null }) pauseLimitReleaseRetries()
    refreshConnection()
    retryFailedAutomatically()
    // Changes signalled during a request wait were skipped; catch up once it ends.
    if (!pollDeferred || cooldowns?.blocking(listOf(RetryScope.REQUESTS)) != null) return
    pollDeferred = false
    scope.launch { poll() }
}
