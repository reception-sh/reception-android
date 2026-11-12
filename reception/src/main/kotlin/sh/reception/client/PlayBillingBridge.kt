package sh.reception.client

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import sh.reception.Log
import kotlin.coroutines.resume

/**
 * The only place the SDK touches Play Billing, which the host may not ship (`compileOnly`; versions 5 and later).
 * Every call checks that the classes exist first; [BillingCalls] is loaded only after that check. Each read opens a
 * short-lived connection and ends it; any error means "unknown".
 */
internal object PlayBillingBridge {
    /** One purchase as automations and paid access see it. */
    class Purchase(
        val products: List<String>,
        val isSubscription: Boolean,
        val isPurchased: Boolean,
        val isAutoRenewing: Boolean,
    ) {
        /** A subscription's identity across reads: its products. */
        val group: String
            get() = products.sorted().joinToString(",")
    }

    private var reportedMissing = false

    private val isPresent: Boolean by lazy {
        try {
            Class.forName("com.android.billingclient.api.BillingClient")
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /** Subscriptions and one-time purchases the person owns, or null when Play Billing is missing or failed. */
    suspend fun purchases(context: Context): List<Purchase>? {
        if (!isPresent) {
            if (!reportedMissing) {
                reportedMissing = true
                Log.debug { "Paid access not read, Play Billing not in the app" }
            }
            return null
        }
        return try {
            BillingCalls.purchases(context.applicationContext)
        } catch (_: LinkageError) {
            Log.debug { "Paid access not read, Play Billing incompatible" }
            null
        } catch (_: IllegalArgumentException) {
            Log.debug { "Paid access not read, Play Billing refused the client" }
            null
        }
    }
}

private object BillingCalls {
    suspend fun purchases(context: Context): List<PlayBillingBridge.Purchase>? {
        val client = client(context)
        try {
            if (!connect(client)) return null
            val subscriptions = query(client, BillingClient.ProductType.SUBS) ?: return null
            val products = query(client, BillingClient.ProductType.INAPP) ?: return null
            return subscriptions + products
        } finally {
            client.endConnection()
        }
    }

    private fun client(context: Context): BillingClient {
        val builder = BillingClient.newBuilder(context).setListener { _, _ -> }
        try {
            builder.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        } catch (_: LinkageError) {
            // Billing 5 and 6 have only the parameterless call, which 8 removed.
            legacyPendingPurchases(builder)
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun legacyPendingPurchases(builder: BillingClient.Builder) {
        builder.enablePendingPurchases()
    }

    private suspend fun connect(client: BillingClient): Boolean = suspendCancellableCoroutine { continuation ->
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val connected = result.responseCode == BillingClient.BillingResponseCode.OK
                if (!connected) Log.debug { "Paid access not read, Play Billing setup ${result.responseCode}" }
                if (continuation.isActive) continuation.resume(connected)
            }

            override fun onBillingServiceDisconnected() {
                if (continuation.isActive) continuation.resume(false)
            }
        })
    }

    private suspend fun query(client: BillingClient, type: String): List<PlayBillingBridge.Purchase>? =
        suspendCancellableCoroutine { continuation ->
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            client.queryPurchasesAsync(params) { result, purchases ->
                val answer = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases.map { purchase ->
                        PlayBillingBridge.Purchase(
                            products = purchase.products,
                            isSubscription = type == BillingClient.ProductType.SUBS,
                            isPurchased = purchase.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED,
                            isAutoRenewing = purchase.isAutoRenewing,
                        )
                    }
                } else {
                    Log.debug { "Paid access not read, Play Billing query ${result.responseCode}" }
                    null
                }
                if (continuation.isActive) continuation.resume(answer)
            }
        }
}
