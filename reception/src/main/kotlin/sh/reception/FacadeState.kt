package sh.reception

import kotlinx.serialization.json.JsonObject

/** Facade values the device payload reports; the paywall, screen and paid access parts fill them. */
internal object FacadeState {
    /** `availablePaywalls` as registration entries. */
    var paywalls: List<JsonObject> = emptyList()

    /** `screens` as the service stores them: `{ id, value? }`. */
    var screens: List<Map<String, String>> = emptyList()

    /** Paid access from Play Billing; null while unknown. */
    var subscribed: Boolean? = null
}
