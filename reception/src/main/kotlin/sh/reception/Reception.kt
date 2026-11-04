package sh.reception

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
