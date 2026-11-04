package sh.reception.client

/** What Play shows about one subscription the person bought, reduced to what automations react to. */
internal enum class SubscriptionState(val raw: String) {
    ACTIVE("active"), CANCELLED("cancelled"), EXPIRED("expired");

    companion object {
        fun from(raw: String?): SubscriptionState? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Subscription changes read from Play Billing on the device (SPEC.md, Automations and paid access). Play shows only
 * current purchases, so of the iOS events Android reports a cancelled renewal and an expiry; trials and billing issues
 * are not visible on the device.
 */
internal object StoreSubscriptions {
    /** Events Reception reports on its own; the app never needs to track them. */
    val events: Set<String> = setOf("subscription_cancelled", "subscription_expired")

    /**
     * The event a change from [old] to [new] means. A subscription seen for the first time is only remembered, so
     * updating the SDK never reports old history; renewing again or resubscribing reports nothing.
     */
    fun event(old: SubscriptionState?, new: SubscriptionState): String? {
        if (old == null || old == new) return null
        return when (new) {
            SubscriptionState.CANCELLED -> if (old == SubscriptionState.EXPIRED) null else "subscription_cancelled"
            SubscriptionState.EXPIRED -> "subscription_expired"
            SubscriptionState.ACTIVE -> null
        }
    }

    /**
     * The state of every subscription now, by its products: a purchased one renews or was cancelled; one [known]
     * earlier that Play no longer lists has expired.
     */
    fun states(known: Map<String, String>, purchases: List<PlayBillingBridge.Purchase>): Map<String, SubscriptionState> {
        val current = purchases.filter { it.isSubscription && it.isPurchased }.associate { purchase ->
            purchase.group to if (purchase.isAutoRenewing) SubscriptionState.ACTIVE else SubscriptionState.CANCELLED
        }
        val gone = known.filter { (group, state) -> group !in current && state != SubscriptionState.EXPIRED.raw }
            .mapValues { SubscriptionState.EXPIRED }
        return current + gone
    }
}
