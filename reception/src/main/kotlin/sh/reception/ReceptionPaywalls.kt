package sh.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.reception.client.preferencesChanged
import kotlin.coroutines.cancellation.CancellationException

/**
 * The paywalls behind the facade: the registered list, the available ones (`paywallAvailability` checks), and the
 * paywall handler. Only the latest check can change [available]. Main thread only; checks run on the main thread.
 */
internal object ReceptionPaywalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var registered: List<ReceptionPaywall> = emptyList()
        private set

    /** Compose state, so an open chat enables or disables its paywall cards when a check finishes. */
    var available: List<ReceptionPaywall> by mutableStateOf(emptyList())
        private set
    var availability: (suspend (String) -> Boolean)? = null
        set(value) {
            field = value
            refresh(invalidate = true)
        }

    /** Compose state too: assigning or removing the handler enables or disables the visible cards. */
    var onPaywall: ((String) -> Unit)? by mutableStateOf(null)
    private var check: Job? = null
    private var generation = 0

    fun register(paywalls: List<ReceptionPaywall>) {
        val validated = ReceptionPaywall.validated(paywalls)
        if (registered == validated) return
        registered = validated
        if (availability != null) {
            // Keep earlier approvals, with current titles, until the new check completes.
            val approved = available.map { it.id }.toSet()
            publish(validated.filter { it.id in approved })
        }
        refresh()
    }

    /** Checks [registered] again; with [invalidate], only info-only entries stay available until the check completes. */
    fun refresh(invalidate: Boolean = false) {
        val generation = ++generation
        check?.cancel()
        check = null
        val availability = availability ?: return publish(registered)
        // Info-only entries never depend on availability, so invalidation keeps them and spares a device update.
        if (invalidate) publish(registered.filter { !it.presentable })
        val candidates = registered
        check = scope.launch {
            val approved = mutableListOf<ReceptionPaywall>()
            for (paywall in candidates) {
                if (generation != this@ReceptionPaywalls.generation) return@launch
                if (!paywall.presentable || isAvailable(availability, paywall.id)) approved += paywall
            }
            // A check that ignored cancellation finishes here; a newer check or reset has replaced it.
            if (generation != this@ReceptionPaywalls.generation) return@launch
            check = null
            publish(approved)
        }
    }

    /** Whether a card may open paywall [id]. */
    fun canOpen(id: String?): Boolean = onPaywall != null && available.any { it.id == id && it.presentable }

    private suspend fun isAvailable(availability: suspend (String) -> Boolean, id: String): Boolean = try {
        availability(id)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // The host's check failed; the paywall stays unavailable rather than taking the app down.
        Log.error { "Paywall availability check failed, paywall unavailable" }
        false
    }

    private fun publish(paywalls: List<ReceptionPaywall>) {
        if (available == paywalls) return
        available = paywalls
        FacadeState.paywalls = paywalls.map { it.payload }
        ReceptionState.session?.preferencesChanged()
    }
}
