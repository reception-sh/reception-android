package sh.reception.viewmodel

import kotlinx.coroutines.launch

// The visible chat becoming active and inactive. The opening work suspends twice (restoration, the load); a chat
// dismissed meanwhile must not start monitoring, loading or polling afterwards, so each step checks its generation.

/** Restores, watches the network, loads, then listens; the network watch comes first so an offline banner shows at once. */
internal fun ChatModel.activate() {
    val generation = ++activationGeneration
    activation?.cancel()
    activation = scope.launch {
        restored()
        if (!isActivation(generation)) return@launch
        startMonitoring()
        load(recheckLimits = true)
        if (isActivation(generation)) startPolling()
    }
}

/** Ends the opening work; [keepsPolling] during a configuration change, whose new screen activates again. */
internal fun ChatModel.deactivate(keepsPolling: Boolean) {
    activationGeneration += 1
    activation?.cancel()
    activation = null
    conversationStart?.cancel()
    conversationStart = null
    if (!keepsPolling) stopPolling()
}

/**
 * The device learned that its conversation exists while the chat is open and knew of none: the team or an automation
 * wrote first, a scheduled automation's message arrived, a push named it. The chat loads it after its opening work, then
 * listens; both belong to the activation, so a close, logout or deletion meanwhile ends them. A load that finds the
 * conversation starts listening itself (`load`), so one this load leaves to another (a load already running, a stale GET
 * loaded again) still ends in a stream.
 */
internal fun ChatModel.conversationStarted() {
    if (!isChatActive || conversationStart?.isActive == true) return
    val generation = activationGeneration
    val opening = activation
    conversationStart = scope.launch {
        opening?.join()
        if (!isActivation(generation)) return@launch
        load()
        if (isActivation(generation)) startPolling()
    }
}

private fun ChatModel.isActivation(generation: Int): Boolean = generation == activationGeneration && current
