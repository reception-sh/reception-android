package sh.reception.client

import sh.reception.Log
import sh.reception.ReceptionState
import sh.reception.viewmodel.cooldownsChanged
import kotlin.math.ceil

/**
 * Rejects work that a known cooldown covers before it reaches the network. The rejection makes no attempt,
 * so it never spends the retry budget.
 */
internal fun DeviceSession.admit(scopes: List<RetryScope>) {
    val cooldown = cooldowns.blocking(scopes) ?: return
    Log.debug { "Request skipped, ${cooldown.scope.raw} paused" }
    val remaining = ceil((cooldown.until - cooldowns.now()) / 1000.0).toInt()
    throw ReceptionAPIError("cooldown", 0, retryAfter = maxOf(1, remaining), retryScope = cooldown.scope)
}

/** Only a response of the service starts a cooldown; storage replies never reach this path. */
internal fun DeviceSession.imposeCooldown(error: ReceptionAPIError) {
    val scope = error.retryScope ?: return
    val seconds = error.retryAfter ?: return
    if (error.status <= 0) return
    cooldowns.impose(scope, seconds)
}

internal fun DeviceSession.cooldownsChanged() {
    if (invalidated || !isCurrent) return
    ReceptionState.chat?.cooldownsChanged()
}
