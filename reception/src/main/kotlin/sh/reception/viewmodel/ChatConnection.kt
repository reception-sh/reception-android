package sh.reception.viewmodel

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.Theme
import sh.reception.client.NetworkMonitor
import sh.reception.client.ReceptionAPIError
import sh.reception.client.hasConversation
import sh.reception.client.hasStartedChat

internal enum class ConnectionState { HIDDEN, OFFLINE, CONNECTING, CONNECTED, PAUSED }

private const val LINK_GRACE_MILLIS = 1_000L

// The banner is derived from three facts only: does the device have a network, is the live stream up, and does a
// server wait hold it back. Retry attempts never touch it, so it cannot flicker while the app keeps trying.

internal fun ChatModel.startMonitoring() {
    val session = session ?: return
    // Only chats that were used before show connection state; an unused chat stays silent.
    if (networkMonitor != null || !isRestored || !session.store.hasStartedChat) return
    var previous: Boolean? = null
    val monitor = NetworkMonitor(session.store.context) { satisfied ->
        if (!current) return@NetworkMonitor
        pathSatisfied = satisfied
        // Observe immediately, but keep networking behind the existing chat activation.
        if (isChatActive && session.store.hasStartedChat) {
            if (satisfied) {
                // Network is back: drop the backoff and reconnect right away.
                if (previous == false) {
                    cancelStream()
                    session.retries["stream"]?.foreground()
                }
                startStream()
                retryFailedAutomatically()
            } else {
                cancelStream()
            }
        }
        refreshConnection()
        previous = satisfied
    }
    networkMonitor = monitor
    monitor.start()
}

internal fun ChatModel.stopMonitoring() {
    networkMonitor?.stop()
    networkMonitor = null
}

/** Recomputes the banner from the facts. Safe to call at any time. */
internal fun ChatModel.refreshConnection() {
    if (networkMonitor == null) return
    // A halted session attempts nothing, so there is no connection state to report.
    if (session?.halt != null) {
        cancelLinkGrace()
        if (connection != ConnectionState.HIDDEN) setConnection(ConnectionState.HIDDEN)
        return
    }
    if (pathSatisfied == false) {
        cancelLinkGrace()
        setConnection(ConnectionState.OFFLINE)
        return
    }
    if (session?.store?.hasConversation != true) {
        cancelLinkGrace()
        if (pathSatisfied == true) setConnection(ConnectionState.HIDDEN)
        return
    }
    if (streamConnected) return
    // A server wait holds the live connection back; sending stays available.
    if (streamPaused) {
        cancelLinkGrace()
        setConnection(ConnectionState.PAUSED)
        return
    }
    // Network is back but the link is not up yet: say so right away.
    if (connection == ConnectionState.OFFLINE) return setConnection(ConnectionState.CONNECTING)
    // Link dropped while online: a one-second grace keeps blinks invisible.
    if (connection == ConnectionState.CONNECTING || linkGrace != null) return
    linkGrace = scope.launch {
        delay(LINK_GRACE_MILLIS)
        if (networkMonitor == null || streamConnected || pathSatisfied == false) return@launch
        linkGrace = null
        setConnection(ConnectionState.CONNECTING)
    }
}

private fun ChatModel.cancelLinkGrace() {
    linkGrace?.cancel()
    linkGrace = null
}

internal fun ChatModel.streamDidConnect() {
    streamConnected = true
    cancelLinkGrace()
    retryFailedAutomatically()
    if (connection !in setOf(ConnectionState.OFFLINE, ConnectionState.CONNECTING, ConnectionState.PAUSED)) return
    setConnection(ConnectionState.CONNECTED)
    connectionDismissal = scope.launch {
        delay(Theme.CONNECTED_MILLIS)
        if (connection == ConnectionState.CONNECTED) setConnection(ConnectionState.HIDDEN)
    }
}

internal fun ChatModel.streamDidDisconnect() {
    streamConnected = false
    refreshConnection()
}

internal fun ChatModel.report(error: Throwable) {
    if (handleReset(error) || !current) return
    val apiError = error as? ReceptionAPIError
    if (apiError?.code == "cancelled") return
    if (apiError?.code in setOf("connection_failed", "upload_failed") || (apiError?.status ?: 0) >= 500) refreshConnection()
}

internal fun ChatModel.setConnection(state: ConnectionState) {
    connectionDismissal?.cancel()
    connection = state
}

internal fun ChatModel.suspendAutomaticRequests() {
    cancelStream()
    cancelLinkGrace()
    if (connection != ConnectionState.HIDDEN) setConnection(ConnectionState.HIDDEN)
    cancelAutomaticRetry()
    userTypingTask?.cancel()
}
