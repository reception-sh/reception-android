package sh.reception.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.reception.FacadeState

/**
 * Whether the person has paid access, read from Play Billing on the device, so "Without a subscription" never reaches
 * someone who pays. Unknown (null, the field is omitted) until a read succeeds; a failed read changes nothing.
 * Main thread only.
 */
internal class PaidAccess(
    private val read: suspend (Context) -> List<PlayBillingBridge.Purchase>? = PlayBillingBridge::purchases,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var check: Job? = null

    /** Runs when paid access changes, so a registered device sends it at once. */
    var onChange: (() -> Unit)? = null

    val current: Boolean?
        get() = FacadeState.subscribed

    /** Reads in the background; at configure and every foreground. */
    fun refresh(context: Context) {
        check?.cancel()
        check = scope.launch { refreshNow(context) }
    }

    /** Reads now and waits, for decisions that must not rest on an older answer. Returns the purchases, null if unknown. */
    suspend fun refreshNow(context: Context): List<PlayBillingBridge.Purchase>? {
        val purchases = read(context) ?: return null
        apply(purchases)
        return purchases
    }

    /** Any purchase in state PURCHASED, a subscription or a one-time product, is paid access. */
    fun apply(purchases: List<PlayBillingBridge.Purchase>) {
        val paid = purchases.any { it.isPurchased }
        if (paid == FacadeState.subscribed) return
        FacadeState.subscribed = paid
        onChange?.invoke()
    }
}
