package sh.reception.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.reception.Log
import sh.reception.ReceptionState
import sh.reception.ReceptionUnread
import sh.reception.viewmodel.retryFailedAutomatically
import sh.reception.viewmodel.startStream
import sh.reception.viewmodel.suspendAutomaticRequests
import kotlin.coroutines.cancellation.CancellationException

// A session across launches and foregrounds: launch registration, foreground retries, and the halts that service
// answers impose and a later successful probe lifts.

internal fun DeviceSession.requestSucceeded(probe: Boolean) {
    if (invalidated || !probe || halt == null) return
    halt = null
    preferenceRetry = RetryState()
    Log.debug { "Session resumed" }
    if (isCurrent) ReceptionState.sessionResumed()
}

internal fun DeviceSession.recordFailure(error: Throwable) {
    if (invalidated || error !is ReceptionAPIError) return
    val reason = when {
        error.status == 401 && error.code == "invalid_app_id" -> DeviceSession.HaltReason.APP_UNAVAILABLE
        error.status == 403 && error.code == "app_disabled" -> DeviceSession.HaltReason.APP_UNAVAILABLE
        error.status == 403 && error.code == "blocked" -> DeviceSession.HaltReason.BLOCKED
        else -> return
    }
    if (halt == reason) {
        Log.debug { "Request skipped, session halted" }
        return
    }
    halt = reason
    suspendPreferenceRetry()
    actionClickTask?.cancel()
    if (isCurrent) {
        ReceptionState.chat?.suspendAutomaticRequests()
        ReceptionState.unreadMonitor.reschedule()
    }
    when {
        reason == DeviceSession.HaltReason.BLOCKED -> Log.info { "Session halted, HTTP 403 blocked" }
        error.code == "invalid_app_id" -> Log.error { "Session halted, HTTP 401 invalid_app_id, check the App ID" }
        else -> Log.error { "Session halted, HTTP 403 app_disabled" }
    }
}

/**
 * Registers a device the team cannot reach yet, once per launch or foreground; a failure waits for the next one.
 * Registration carries no push token, so the unread refresh that follows sends it with the device update.
 */
internal fun DeviceSession.registerIfNeeded() {
    if (invalidated || halt != null || isRegistered || launchRegistration != null) return
    lateinit var task: Job
    // A later main-looper turn, not inline: a token or identity staged right after `configure` goes with it.
    task = scope.launch(RequestContext(launch = true) + Dispatchers.Main, start = CoroutineStart.LAZY) {
        try {
            prepared()
            // A stored session needs no registration; preparing it refreshes unread instead.
            if (isRegistered) return@launch
            token()
            if (isCurrent) ReceptionUnread.refresh(updateDevice = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            Log.debug { "Launch registration deferred to the next foreground" }
        } finally {
            if (launchRegistration === task) launchRegistration = null
        }
    }
    launchRegistration = task
    task.start()
}

internal fun DeviceSession.becameActive() {
    preferenceRetry.foreground()
    retries.values.forEach { it.foreground() }
    if (halt != null) return
    registerIfNeeded()
    retryPreferencesIfNeeded()
    if (isCurrent) {
        ReceptionState.chat?.retryFailedAutomatically()
        ReceptionState.chat?.startStream()
    }
}
