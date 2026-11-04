package sh.reception.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import sh.reception.FacadeState
import sh.reception.Log
import sh.reception.ReceptionIdentity
import sh.reception.ReceptionLocalization
import sh.reception.ReceptionState

/**
 * The current access token. Without one in memory it refreshes. A registered device may always ask; an unused one
 * registers only at launch or foreground, on the user's send or for an automation event.
 */
internal suspend fun DeviceSession.token(): String {
    ensureValid()
    val context = RequestContext.current()
    val registered = try {
        storedCredentials() != null
    } catch (_: ReceptionAPIError) {
        false
    }
    ensureValid()
    if (!registered && !store.hasStartedChat && !context.automation && !context.launch) {
        throw ReceptionAPIError("chat_not_started", 0)
    }
    if (halt != null && context.probe?.available != true) {
        Log.debug { "Request skipped, session halted" }
        throw ReceptionAPIError("session_halted", 0)
    }
    settledToken()?.let { return it }
    if (storedCredentials() != null) return renewedToken(replacing = null)
    ensureValid()
    // The read may suspend: a caller that read at the same time can have registered or started to meanwhile.
    settledToken()?.let { return it }
    if (context.probe != null) registrationAllowed = true
    if (!registrationAllowed && !context.automation && !context.launch) throw ReceptionAPIError("chat_not_started", 0)
    // Launch registration belongs to the foreground; the reads above may have outlasted it.
    if (context.launch && !ApplicationLifecycle.isActive) throw ReceptionAPIError("cancelled", 0)
    return handedOut(register())
}

/** A token other than [replacing]: one a concurrent refresh already stored, or a new one. Refreshes one at a time. */
internal suspend fun DeviceSession.renewedToken(replacing: String?): String {
    ensureValid()
    settledToken(replacing)?.let { return it }
    refresh?.let { return handedOut(it.awaitShared()) }
    val secret = storedCredentials()?.sessionSecret ?: throw ReceptionAPIError("session_revoked", 401)
    ensureValid()
    // The read may suspend: a concurrent caller can have registered or started the renewal meanwhile.
    settledToken(replacing)?.let { return it }
    refresh?.let { return handedOut(it.awaitShared()) }
    // A renewal limit fails callers at once instead of holding their requests for the whole wait.
    admit(listOf(RetryScope.SESSION))
    // The renewal belongs to the session: only its own end clears it, so a cancelled caller never lets a second
    // renewal start (each one invalidates the previous token).
    lateinit var task: Deferred<String>
    task = scope.async(RequestContext.current(), start = CoroutineStart.LAZY) {
        try {
            refreshBackoff?.awaitShared()
            ensureActive()
            admit(listOf(RetryScope.SESSION))
            refreshToken(secret)
        } finally {
            if (refresh === task) refresh = null
        }
    }
    refresh = task
    return handedOut(task.awaitShared())
}

/** The token in memory other than [replacing], once [settled]. */
private suspend fun DeviceSession.settledToken(replacing: String? = null): String? {
    settled()
    return accessToken?.takeIf { it != replacing }
}

/** [token], from a renewal or registration that may have outlasted another change to the credentials, once [settled]. */
private suspend fun DeviceSession.handedOut(token: String): String {
    settled()
    return token
}

/**
 * The gate before any token leaves this file: no registration in flight, no unsaved credentials, and the session not
 * ended. A registration sets the token before its save, and a device update may store a new verified user while a
 * renewal awaits the network; a caller waits for those saves (and fails with them) instead of sending requests for a
 * session that may not survive a restart. A logout during a save also clears the unsaved flag, so the gate checks
 * the session itself last: an ended session's token never leaves.
 */
private suspend fun DeviceSession.settled() {
    while (true) {
        registration?.awaitShared()
        ensureValid()
        persistPendingCredentials()
        ensureValid()
        // Both steps may suspend: another registration may have started, or newer credentials arrived during the save.
        if (registration == null && !(credentialsUnsaved && credentials != null)) return
    }
}

/** An ended session (logout, deletion, reconfiguration) hands out no token. */
private fun DeviceSession.ensureValid() {
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
}

@Serializable
private class RegistrationResponse(val token: String, val sessionSecret: String, val verifiedUserId: String? = null)

@Serializable
private class TokenResponse(val token: String)

@Serializable
private class SessionSecretInput(val sessionSecret: String)

private suspend fun DeviceSession.register(): String {
    // Like a renewal, the registration is cleared only when it ends, never by a cancelled caller.
    lateinit var task: Deferred<String>
    task = scope.async(RequestContext.current(), start = CoroutineStart.LAZY) {
        try {
            registerDevice()
        } finally {
            if (registration === task) registration = null
        }
    }
    registration = task
    return task.awaitShared()
}

private suspend fun DeviceSession.registerDevice(): String {
    Log.debug { "Device registration started" }
    val badge = ReceptionState.updatesAppBadge
    val paywalls = FacadeState.paywalls
    val language = ReceptionLocalization.appLanguage
    val screens = FacadeState.screens
    val subscribed = FacadeState.subscribed
    val identityToken = stagedIdentityToken
    val response = try {
        api.request<RegistrationResponse>("devices", "POST",
            body = DeviceInfo.payload(store.context, pending, identityToken?.value, registration = true))
    } catch (error: ReceptionAPIError) {
        // The send fails and keeps Retry; the next registration goes without this token.
        if (error.rejectsIdentityToken && identityToken != null) identityRejected(identityToken, error)
        throw error
    }
    currentCoroutineContext().ensureActive()
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
    accessToken = response.token
    credentials = SessionCredentials(response.sessionSecret, response.verifiedUserId)
    credentialsUnsaved = true
    identityToken?.let { ReceptionIdentity.sent(it) }
    persistPendingCredentials()
    synchronizedPaywalls = paywalls
    synchronizedAppBadge = badge
    synchronizedLanguage = language
    synchronizedScreens = screens
    synchronizedSubscribed = subscribed
    Log.info { "Device registered" }
    return response.token
}

private suspend fun DeviceSession.refreshToken(secret: String): String {
    try {
        // Part of an already admitted request: blocked devices may refresh, so no halt check here.
        val output = api.send("devices/me/token", TokenResponse.serializer(), "POST", token = null,
            body = ReceptionAPI.encode(SessionSecretInput(secret)))
        if (invalidated || credentials?.sessionSecret != secret) throw ReceptionAPIError("cancelled", 0)
        accessToken = output.token
        refreshFailures = 0
        refreshBackoff = null
        Log.debug { "Access token refreshed" }
        return output.token
    } catch (error: ReceptionAPIError) {
        if (error.status == 401 && error.code == "session_revoked") {
            if (!invalidated && credentials?.sessionSecret == secret) sessionRevoked()
            throw error
        }
        // A server wait becomes the stored session cooldown in requestFailed; other transient failures back off.
        if (!invalidated && ReceptionAPIError.isTransient(error) && error.retryScope == null) {
            refreshFailures = minOf(refreshFailures, 6) + 1
            val delay = Backoff.delay(refreshFailures)
            // The delay belongs to the session: another caller must not bypass a failed refresh's backoff.
            refreshBackoff = scope.async { refreshSleep(delay) }
        }
        requestFailed(error, "devices/me/token")
        throw error
    }
}

/** The server no longer knows this session: forget it and continue with a fresh one. */
private fun DeviceSession.sessionRevoked() {
    Log.error { "Session revoked, HTTP 401 session_revoked, local session reset" }
    accessToken = null
    credentials = null
    credentialsUnsaved = false
    if (isCurrent) ReceptionState.resetChat(revision = 0, deviceDeleted = true)
}
