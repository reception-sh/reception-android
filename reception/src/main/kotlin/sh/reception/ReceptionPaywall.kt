package sh.reception

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A paywall your app can show, listed in `Reception.paywalls`. Agents can send it as a card; a tap calls
 * `Reception.onPaywall` with its [id]. [presentable] false only lists it for your team (for example an offering without
 * a paywall): it is always reported, never checked with `paywallAvailability`, and never sent as a card.
 */
public data class ReceptionPaywall @JvmOverloads constructor(
    public val id: String,
    public val title: String,
    public val source: Source? = null,
    public val products: List<Product> = emptyList(),
    public val presentable: Boolean = true,
) {
    /**
     * Where the paywall is built. Your team sees it beside the paywall in the dashboard. Any lowercase name works, so
     * a vendor the SDK doesn't name yet can still be reported.
     */
    public data class Source(public val rawValue: String) {
        /** Lowercase letters, digits and hyphens, 1–32 characters, like the dashboard expects. */
        internal val isValid: Boolean
            get() = validSource.matches(rawValue)

        public companion object {
            @JvmField public val RevenueCat: Source = Source("revenuecat")
            @JvmField public val Superwall: Source = Source("superwall")
            @JvmField public val Adapty: Source = Source("adapty")
            @JvmField public val App: Source = Source("app")
        }
    }

    /**
     * A product the paywall sells, shown to your team in the dashboard. [price] is the localized price, like "$4.99";
     * [period] the renewal period as an ISO 8601 duration, like "P1M" (null for one-time purchases); [trial] the free
     * trial as an ISO 8601 duration, like "P1W".
     */
    public data class Product @JvmOverloads constructor(
        public val id: String,
        public val price: String? = null,
        public val period: String? = null,
        public val trial: String? = null,
    )

    /** The registration entry; source, products and the info-only flag are sent only when the app sets them. */
    internal val payload: JsonObject
        get() = JsonObject(buildMap {
            put("id", JsonPrimitive(id))
            put("title", JsonPrimitive(title))
            source?.let { put("source", JsonPrimitive(it.rawValue)) }
            if (products.isNotEmpty()) put("products", JsonArray(products.map { it.payload }))
            if (!presentable) put("presentable", JsonPrimitive(false))
        })

    internal companion object {
        /** Bounds what products add to device requests, so a large catalog never pushes registration past the size limit. */
        const val PRODUCT_BUDGET = 40
        const val LIMIT = 20
        private val validId = Regex("^[A-Za-z0-9_.-]+$")
        private val validSource = Regex("^[a-z0-9-]{1,32}$")
        private val isoPeriod = Regex("^P([0-9]{1,3}[DWMY]){1,3}$")

        fun validated(paywalls: List<ReceptionPaywall>): List<ReceptionPaywall> {
            val result = mutableListOf<ReceptionPaywall>()
            val ids = mutableSetOf<String>()
            var budget = PRODUCT_BUDGET
            for (paywall in paywalls) {
                val id = paywall.id.trim()
                val title = paywall.title.trim()
                if (result.size >= LIMIT || id.length !in 1..64 || !validId.matches(id) || title.length !in 1..60 ||
                    !ids.add(id)
                ) {
                    Log.debug { "Invalid or duplicate paywall dropped" }
                    continue
                }
                val products = validatedProducts(paywall.products).take(budget)
                budget -= products.size
                // Details describe the paywall for the team only, so an invalid source is dropped instead of the paywall.
                val source = paywall.source?.takeIf { it.isValid }
                result += ReceptionPaywall(id, title, source, products, paywall.presentable)
            }
            return result
        }

        /** Products describe the paywall for the team only, so a malformed detail is dropped instead of the paywall. */
        private fun validatedProducts(products: List<Product>): List<Product> = products.take(10).mapNotNull { product ->
            val id = product.id.trim()
            if (id.length !in 1..100) return@mapNotNull null
            val price = product.price?.trim()?.takeIf { it.length in 1..40 }
            Product(id, price, product.period?.takeIf(isoPeriod::matches), product.trial?.takeIf(isoPeriod::matches))
        }

        private val Product.payload: JsonObject
            get() = JsonObject(buildMap {
                put("id", JsonPrimitive(id))
                price?.let { put("price", JsonPrimitive(it)) }
                period?.let { put("period", JsonPrimitive(it)) }
                trial?.let { put("trial", JsonPrimitive(it)) }
            })
    }
}
