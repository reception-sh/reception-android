package sh.reception

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.reception.client.ReceptionAPIError
import sh.reception.client.RequestContext
import sh.reception.client.RequestProbe
import sh.reception.client.isRegistered
import sh.reception.client.revocationSecret
import sh.reception.client.storedCredentials

/** Push token, logout and data deletion behind the facade. Main thread only. */
internal object ReceptionAccount {
    private val fcmToken = Regex("^[A-Za-z0-9_:-]{1,4096}$")
    private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setPushToken(token: String) {
        val session = ReceptionState.session ?: run {
            Log.error { "setPushToken called before configure, ignored" }
            Log.debug { "Push token ignored, not configured" }
            return
        }
        if (token.isEmpty()) return Log.debug { "Push token ignored, empty token" }
        if (!fcmToken.matches(token)) return Log.debug { "Push token ignored, not an FCM registration token" }
        // FCM has no sandbox; the service accepts only PRODUCTION for Android devices.
        session.stage { it.copy(pushToken = token, pushEnvironment = "PRODUCTION") }
        Log.debug { "Push token staged, ${token.length} chars, production" }
        if (session.isRegistered) ReceptionUnread.refresh(updateDevice = true)
    }

    /** Dismisses chat, resets locally immediately, and retries revoking the old session while the app is active. */
    fun logout() {
        val session = ReceptionState.session ?: return Log.error { "logout called before configure, ignored" }
        val queued = ReceptionState.logoutRevocations?.enqueue(session.api, session.revocationSecret())
        ReceptionIdentity.forget()
        Log.info { "Logged out, session revocation queued" }
        // The old session's credentials are deleted only after its revocation is stored.
        ReceptionState.resetLocal(after = queued)
        ReceptionPaywalls.refresh(invalidate = true)
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
            ReceptionPaywalls.refresh(invalidate = true)
        }
        ReceptionState.onEvent?.invoke(ReceptionEvent.DataDeleted)
    }

    /** [deleteData] for callers without coroutines: [callback] receives null on success, on the main thread. */
    fun deleteData(callback: (ReceptionError?) -> Unit) {
        callbacks.launch {
            val error = try {
                deleteData()
                null
            } catch (error: ReceptionError) {
                error
            }
            callback(error)
        }
    }
}
