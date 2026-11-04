package sh.reception

import kotlinx.coroutines.withContext
import sh.reception.client.ReceptionAPIError
import sh.reception.client.RequestContext
import sh.reception.client.RequestProbe
import sh.reception.client.isRegistered
import sh.reception.client.storedCredentials

/** Push token, logout and data deletion behind the facade. Main thread only. */
internal object ReceptionAccount {
    private val fcmToken = Regex("^[A-Za-z0-9_:-]{1,4096}$")

    fun setPushToken(token: String) {
        val store = ReceptionState.session?.store ?: run {
            Log.error { "setPushToken called before configure, ignored" }
            Log.debug { "Push token ignored, not configured" }
            return
        }
        if (token.isEmpty()) return Log.debug { "Push token ignored, empty token" }
        if (!fcmToken.matches(token)) return Log.debug { "Push token ignored, not an FCM registration token" }
        // FCM has no sandbox; the service accepts only PRODUCTION for Android devices.
        store.pending = store.pending.copy(pushToken = token, pushEnvironment = "PRODUCTION")
        Log.debug { "Push token staged, ${token.length} chars, production" }
        if (ReceptionState.session?.isRegistered == true) ReceptionState.refreshUnread(updateDevice = true)
    }

    /** Dismisses chat, resets locally immediately, and retries revoking the old session while the app is active. */
    fun logout() {
        val session = ReceptionState.session ?: return Log.error { "logout called before configure, ignored" }
        try {
            session.storedCredentials()?.sessionSecret?.let { ReceptionState.logoutRevocations?.enqueue(session.api, it) }
        } catch (_: ReceptionAPIError) {
            Log.error { "Session revocation skipped, secure storage unavailable" }
        }
        ReceptionIdentity.forget()
        Log.info { "Logged out, session revocation queued" }
        ReceptionState.resetLocal()
    }

    /**
     * Deletes the current device's support data, then resets the local session. Failures preserve the session for
     * retry. An absent or ended session, or an unavailable App ID, can complete without erasing earlier server records.
     */
    suspend fun deleteData() {
        val session = ReceptionState.session ?: return Log.error { "deleteData called before configure, ignored" }
        val generation = ReceptionState.presentationRevision
        val secret = try {
            session.storedCredentials()?.sessionSecret
        } catch (_: ReceptionAPIError) {
            Log.error { "Data deletion failed, secure storage unavailable" }
            throw ReceptionError("secure_storage_unavailable", 0)
        }
        if (secret == null) {
            Log.info { "Local deletion completed, no stored session" }
        } else {
            try {
                withContext(RequestContext(probe = RequestProbe())) { session.api.deleteDevice(secret) }
                Log.info { "Data deleted" }
            } catch (error: ReceptionAPIError) {
                if (error.status == 401 && error.code == "invalid_app_id") {
                    // The server no longer accepts this app's configuration, so only local completion is known.
                    Log.info { "Local deletion completed, App ID unavailable" }
                } else {
                    if (error.status > 0) {
                        Log.error { "Data deletion failed, HTTP ${error.status} ${error.code}" }
                    } else {
                        Log.error { "Data deletion failed, network error" }
                        error.networkError?.let { Log.debug { "Data deletion failed, network error, $it" } }
                    }
                    throw ReceptionError(error.code, error.status)
                }
            }
        }
        // A response from an old session must not erase a newly configured or logged-in session.
        if (ReceptionState.presentationRevision == generation) {
            ReceptionIdentity.forget()
            ReceptionState.resetLocal()
        }
        ReceptionState.onEvent?.invoke(ReceptionEvent.DataDeleted)
    }
}
