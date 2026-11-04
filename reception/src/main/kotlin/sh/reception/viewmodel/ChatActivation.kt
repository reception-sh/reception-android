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
    if (!keepsPolling) stopPolling()
}

private fun ChatModel.isActivation(generation: Int): Boolean = generation == activationGeneration && current
