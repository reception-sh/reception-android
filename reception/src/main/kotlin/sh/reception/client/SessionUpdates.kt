package sh.reception.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import sh.reception.FacadeState
import sh.reception.Log
import sh.reception.ReceptionIdentity
import sh.reception.ReceptionLocalization
import sh.reception.ReceptionState
import kotlin.coroutines.cancellation.CancellationException

// Serialized device updates: preference changes during a request are sent after it, with in-memory acknowledgments.

internal suspend fun DeviceSession.update() {
    if (invalidated) throw ReceptionAPIError("cancelled", 0)
    if (halt != null && RequestContext.current().probe?.available != true) {
        Log.debug { "Request skipped, session halted" }
        throw ReceptionAPIError("session_halted", 0)
    }
    updateGeneration += 1
    updateTask?.let { return it.awaitShared() }
    lateinit var task: Deferred<Unit>
    task = scope.async(RequestContext.current(), start = CoroutineStart.LAZY) {
        try {
            sendUpdates()
        } finally {
            if (updateTask === task) updateTask = null
        }
    }
    updateTask = task
    try {
        task.awaitShared()
        Log.debug { "Device updated" }
        flushActionClicks()
    } catch (error: Throwable) {
        // A lost response can leave the server with a value we did not acknowledge.
        synchronizedAppBadge = null
        throw error
    }
}

private suspend fun DeviceSession.sendUpdates() {
    var withoutIdentityToken = false
    do {
        var resend = false
        currentCoroutineContext().ensureActive()
        if (invalidated) throw ReceptionAPIError("cancelled", 0)
        val sentGeneration = updateGeneration
        val language = ReceptionLocalization.appLanguage
        val badge = ReceptionState.updatesAppBadge
        val paywalls = FacadeState.paywalls
        val subscribed = FacadeState.subscribed
        val screens = FacadeState.screens
        val identityToken = if (withoutIdentityToken) null else unsentIdentityToken
        val response = try {
            api.request<UpdateResponse>("devices/me", "PATCH", authenticated = true,
                body = DeviceInfo.payload(store.context, pending, identityToken?.value))
        } catch (error: ReceptionAPIError) {
            if (!error.rejectsIdentityToken || identityToken == null) throw error
            identityRejected(identityToken, error)
            if (invalidated) throw ReceptionAPIError("cancelled", 0)
            // A rejected token never blocks the chat: this update is sent once more without it.
            withoutIdentityToken = true
            resend = true
            continue
        }
        currentCoroutineContext().ensureActive()
        if (invalidated) throw ReceptionAPIError("cancelled", 0)
        storeVerifiedUserId(response.verifiedUserId)
        response.push?.let { store.pushActive = it }
        identityToken?.let { ReceptionIdentity.sent(it) }
        synchronizedPaywalls = paywalls
        synchronizedLanguage = language
        synchronizedAppBadge = badge
        synchronizedSubscribed = subscribed
        synchronizedScreens = screens
        // Preference changes during a request must follow its older values.
    } while (resend || preferencesNeedSync || sentGeneration != updateGeneration)
}

@Serializable
private class UpdateResponse(val verifiedUserId: String? = null, val push: Boolean? = null)

internal fun DeviceSession.preferencesChanged() {
    preferenceRetry = RetryState()
    suspendPreferenceRetry()
    retryPreferencesIfNeeded()
}

internal val DeviceSession.preferencesNeedSync: Boolean
    get() = synchronizedPaywalls != FacadeState.paywalls || synchronizedLanguage != ReceptionLocalization.appLanguage ||
        synchronizedAppBadge != ReceptionState.updatesAppBadge || synchronizedSubscribed != FacadeState.subscribed ||
        synchronizedScreens != FacadeState.screens

internal fun DeviceSession.retryPreferencesIfNeeded() {
    if (invalidated || halt != null || !isRegistered || preferenceRetry.stopped || !preferencesNeedSync ||
        preferenceRetryTask != null || !ApplicationLifecycle.isActive
    ) return
    lateinit var task: Job
    task = scope.launch(start = CoroutineStart.LAZY) {
        try {
            while (!invalidated && halt == null && preferencesNeedSync && !preferenceRetry.stopped) {
                preferenceRetry.delay?.let { delay(it) }
                if (halt != null) return@launch
                try {
                    update()
                    preferenceRetry = RetryState()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    if (invalidated) return@launch
                    if (ReceptionState.handleReset(this@retryPreferencesIfNeeded, error)) return@launch
                    if (halt != null) return@launch
                    preferenceRetry.failed(error)
                }
            }
        } finally {
            if (preferenceRetryTask === task) preferenceRetryTask = null
        }
    }
    preferenceRetryTask = task
    task.start()
}

internal fun DeviceSession.suspendPreferenceRetry() {
    preferenceRetryTask?.cancel()
    preferenceRetryTask = null
}

