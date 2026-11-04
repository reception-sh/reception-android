package sh.reception

import kotlinx.serialization.json.JsonObject

/** In-app support chat. Every member is called from the main thread. */
public object Reception {
    /** Minimum severity of developer logs, from quietest to most detailed. */
    public enum class LogLevel { OFF, ERROR, INFO, DEBUG }

    /** Version of the SDK, for example "1.0.0". Sent with device registration and updates. */
    @JvmStatic
    public val version: String
        get() = SDKVersion.current

    /** Minimum level that is emitted. Default [LogLevel.INFO]. Set before `configure`. */
    @JvmStatic
    public var logLevel: LogLevel
        get() = Log.level
        set(value) {
            Log.level = value
        }

    /** Receives every emitted line instead of Logcat. `null` (default) logs with the tag "Reception". */
    @JvmStatic
    public var logHandler: ((LogLevel, String) -> Unit)?
        get() = Log.handler
        set(value) {
            Log.handler = value
        }
}

/** Facade values the device payload reports; the paywall, screen, paid access and appearance parts fill them. */
internal object FacadeState {
    /** `availablePaywalls` as registration entries. */
    var paywalls: List<JsonObject> = emptyList()

    /** `screens` as the service stores them: `{ id, value? }`. */
    var screens: List<Map<String, String>> = emptyList()

    /** Paid access from Play Billing; null while unknown. */
    var subscribed: Boolean? = null

    /** The host appearance report; null until the appearance model exists. */
    var appearanceReport: JsonObject? = null
}
