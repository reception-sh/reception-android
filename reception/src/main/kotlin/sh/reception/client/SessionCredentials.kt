package sh.reception.client

import kotlinx.serialization.Serializable

/** The only persisted part of a device session. The access token stays in memory. Its reads and writes follow. */
@Serializable
internal data class SessionCredentials(val sessionSecret: String, val verifiedUserId: String? = null)

/** Whether the loaded session has credentials; false until the session is prepared or while storage is unreadable. */
internal val DeviceSession.isRegistered: Boolean
    get() = credentials != null

/** Memory first, then secure storage on the storage thread. Throws when secure storage cannot be read. */
internal suspend fun DeviceSession.storedCredentials(): SessionCredentials? {
    prepared()
    credentials?.let { return it }
    if (!credentialsUnreadable) return null
    val loaded = Storage.run { store.loadCredentials() }
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
    credentialsUnreadable = false
    if (credentials == null) credentials = loaded
    return credentials
}

/**
 * The session secret a logout revokes, read on the storage thread when this session has not loaded it. It does not
 * depend on the session's scope, which the logout cancels.
 */
internal fun DeviceSession.revocationSecret(): suspend () -> String? {
    val known = credentials ?: return {
        Storage.run {
            store.open()
            store.loadCredentials()?.sessionSecret
        }
    }
    return { known.sessionSecret }
}

/**
 * Keeps registrations and identity changes retryable until their credentials are on disk: a failed write leaves them
 * unsaved, so the next token use tries again and fails with `secure_storage_unavailable` instead of using them.
 */
internal suspend fun DeviceSession.persistPendingCredentials() {
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
    val credentials = credentials
    if (!credentialsUnsaved || credentials == null) return
    Storage.run { store.save(credentials) }
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
    // A newer value that arrived during the write stays unsaved until its own write.
    if (this.credentials === credentials) credentialsUnsaved = false
}
