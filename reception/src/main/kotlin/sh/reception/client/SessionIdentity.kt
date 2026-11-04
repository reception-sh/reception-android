package sh.reception.client

import sh.reception.Log
import sh.reception.ReceptionError
import sh.reception.ReceptionIdentity

/** Registrations always carry the staged token, so a new session after a reset stays verified. */
internal val DeviceSession.stagedIdentityToken: IdentityToken?
    get() = if (isCurrent) ReceptionIdentity.token else null

/** Device updates carry the token only until the service has accepted it. */
internal val DeviceSession.unsentIdentityToken: IdentityToken?
    get() = stagedIdentityToken?.takeIf { !it.sent }

/** The service's value replaces the stored one after every registration and device update. */
internal fun DeviceSession.storeVerifiedUserId(userId: String?) {
    val current = credentials ?: return
    if (UserIdentity.matches(current.verifiedUserId, userId) && !credentialsUnsaved) return
    credentials = current.copy(verifiedUserId = userId)
    credentialsUnsaved = true
    try {
        persistPendingCredentials()
    } catch (_: ReceptionAPIError) {
        Log.error { "Verified user not stored, secure storage unavailable" }
    }
}

/** A rejected token is dropped and reported; a token for another user starts that user's own session. */
internal fun DeviceSession.identityRejected(token: IdentityToken, error: ReceptionAPIError) {
    if (invalidated || !isCurrent || stagedIdentityToken?.value != token.value) return
    if (error.code == "identity_mismatch") {
        ReceptionIdentity.switchUser(token)
    } else {
        ReceptionIdentity.rejected(token, ReceptionError(error.code, error.status))
    }
}
