package sh.reception.client

import sh.reception.Log

/**
 * The last check before the network. A known cooldown is checked first, so it never spends the probe that lets
 * one user action through a halted session. [probing]: the retry of a request that is the probe keeps its permission.
 */
internal suspend fun DeviceSession.authorizeRequest(
    scopes: List<RetryScope> = emptyList(),
    probing: Boolean = false,
): Boolean {
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
    admit(scopes)
    val probe = RequestContext.current().probe
    val permitted = probing || probe?.available == true
    probe?.available = false
    if (halt != null && !permitted) {
        Log.debug { "Request skipped, session halted" }
        throw ReceptionAPIError("session_halted", 0)
    }
    return halt != null && permitted
}

internal fun DeviceSession.requestFailed(error: ReceptionAPIError, path: String, method: String? = null) {
    if (invalidated || error.code == "cancelled" || error.code == "session_halted" || error.isCooldown) return
    imposeCooldown(error)
    when {
        path == "devices" && method == "POST" -> Log.error { "Device registration failed, HTTP ${error.status} ${error.code}" }
        path == "devices/me" && method == "PATCH" -> Log.error { "Device update failed, HTTP ${error.status} ${error.code}" }
        path == "devices/me/token" -> Log.error { "Access token refresh failed, HTTP ${error.status} ${error.code}" }
    }
    recordFailure(error)
}
