package sh.reception.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import sh.reception.FacadeState
import sh.reception.Log
import sh.reception.ReceptionIdentity
import sh.reception.ReceptionLocalization
import sh.reception.ReceptionState

internal val DeviceSession.isRegistered: Boolean
    get() = try {
        storedCredentials() != null
    } catch (_: ReceptionAPIError) {
        false
    }

/** Memory first, then secure storage. Throws when secure storage cannot be read. */
internal fun DeviceSession.storedCredentials(): SessionCredentials? {
    credentials?.let { return it }
    credentials = store.loadCredentials()
    return credentials
}

/**
 * The current access token. Without one in memory it refreshes. A registered device may always ask; an unused one
 * registers only at launch or foreground, on the user's send or for an automation event.
 */
internal suspend fun DeviceSession.token(): String {
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
    val context = RequestContext.current()
    if (!isRegistered && !store.hasStartedChat && !context.automation && !context.launch) {
        throw ReceptionAPIError("chat_not_started", 0)
    }
    if (halt != null && context.probe?.available != true) {
        Log.debug { "Request skipped, session halted" }
        throw ReceptionAPIError("session_halted", 0)
    }
    persistPendingCredentials()
    accessToken?.let { return it }
    registration?.let { return it.awaitShared() }
    if (storedCredentials() != null) return renewedToken(replacing = null)
    if (context.probe != null) registrationAllowed = true
    if (!registrationAllowed && !context.automation && !context.launch) throw ReceptionAPIError("chat_not_started", 0)
    return register()
}

/** A token other than [replacing]: one a concurrent refresh already stored, or a new one. Refreshes one at a time. */
internal suspend fun DeviceSession.renewedToken(replacing: String?): String {
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
    accessToken?.takeIf { it != replacing }?.let { return it }
    refresh?.let { return it.awaitShared() }
    val secret = storedCredentials()?.sessionSecret ?: throw ReceptionAPIError("session_revoked", 401)
    // A renewal limit fails callers at once instead of holding their requests for the whole wait.
    admit(listOf(RetryScope.SESSION))
    val task = scope.async(RequestContext.current(), start = CoroutineStart.LAZY) {
        refreshBackoff?.awaitShared()
        ensureActive()
        admit(listOf(RetryScope.SESSION))
        refreshToken(secret)
    }
    refresh = task
    try {
        return task.awaitShared()
    } finally {
        if (refresh === task) refresh = null
    }
}

/** Keeps registrations and identity changes retryable until their credentials are safely stored. */
internal fun DeviceSession.persistPendingCredentials() {
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
    val credentials = credentials
    if (!credentialsUnsaved || credentials == null) return
    store.save(credentials)
    credentialsUnsaved = false
}

@Serializable
private class RegistrationResponse(val token: String, val sessionSecret: String, val verifiedUserId: String? = null)

@Serializable
private class TokenResponse(val token: String)

@Serializable
private class SessionSecretInput(val sessionSecret: String)

private suspend fun DeviceSession.register(): String {
    val task = scope.async(RequestContext.current(), start = CoroutineStart.LAZY) {
        Log.debug { "Device registration started" }
        val badge = ReceptionState.updatesAppBadge
        val paywalls = FacadeState.paywalls
        val language = ReceptionLocalization.appLanguage
        val screens = FacadeState.screens
        val subscribed = FacadeState.subscribed
        val identityToken = stagedIdentityToken
        val response = try {
            api.request<RegistrationResponse>("devices", "POST",
                body = DeviceInfo.payload(store.context, store.pending, identityToken?.value, registration = true))
        } catch (error: ReceptionAPIError) {
            // The send fails and keeps Retry; the next registration goes without this token.
            if (error.rejectsIdentityToken && identityToken != null) identityRejected(identityToken, error)
            throw error
        }
        ensureActive()
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
        response.token
    }
    registration = task
    try {
        return task.awaitShared()
    } finally {
        if (registration === task) registration = null
    }
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
