package sh.reception.client

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import sh.reception.FacadeState

/**
 * Whether the person has paid access, read from Play Billing on the device, so "Without a subscription" never reaches
 * someone who pays. Unknown (null, the field is omitted) until a read succeeds and again after a read fails: an older
 * answer is never sent as current. Only the latest read publishes; a newer one cancels the one before. Main thread only.
 */
internal class PaidAccess(
    private val read: suspend (Context) -> List<PlayBillingBridge.Purchase>? = PlayBillingBridge::purchases,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reading: Deferred<List<PlayBillingBridge.Purchase>?>? = null
    private var generation = 0

    /** Runs when paid access changes to a known value, so a registered device sends it at once. */
    var onChange: (() -> Unit)? = null

    val current: Boolean?
        get() = FacadeState.subscribed

    /** Reads in the background; at configure and every foreground. */
    fun refresh(context: Context) {
        start(context)
    }

    /**
     * Reads now and waits, for decisions that must not rest on an older answer. A read that a newer one supersedes
     * waits for the newer one's answer. Returns the purchases, null if unknown.
     */
    suspend fun refreshNow(context: Context): List<PlayBillingBridge.Purchase>? {
        var task = start(context)
        while (true) {
            try {
                return task.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                task = reading?.takeIf { it !== task } ?: throw cancelled
            }
        }
    }

    private fun start(context: Context): Deferred<List<PlayBillingBridge.Purchase>?> {
        reading?.cancel()
        generation += 1
        val started = generation
        return scope.async {
            val purchases = read(context)
            // A read that ignores cancellation can still finish after a newer one; only the latest publishes.
            if (started == generation) publish(purchases)
            purchases
        }.also { reading = it }
    }

    /** Internal for tests: a read's answer; null (a failure) makes paid access unknown. */
    fun publish(purchases: List<PlayBillingBridge.Purchase>?) {
        val paid = purchases?.any { it.isPurchased }
        if (paid == FacadeState.subscribed) return
        FacadeState.subscribed = paid
        if (paid != null) onChange?.invoke()
    }
}
