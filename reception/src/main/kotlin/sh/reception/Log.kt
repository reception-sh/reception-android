package sh.reception

import android.util.Log as AndroidLog

// Never log access tokens, session secrets, identity tokens or their claims, the identity secret,
// push token values, user IDs, names, email addresses, metadata values, message text, image URLs,
// conversation content, or base URL credentials.
// Allowed values are HTTP status and Reception error codes, counts, revision numbers,
// opaque conversation IDs, push environments, language tags, and development hostnames.
internal object Log {
    private const val TAG = "Reception"

    private class Settings(
        val level: Reception.LogLevel,
        val handler: ((Reception.LogLevel, String) -> Unit)?,
    )

    // One immutable snapshot, so a line never pairs one thread's level with another's handler.
    @Volatile
    private var settings = Settings(Reception.LogLevel.INFO, null)

    var level: Reception.LogLevel
        get() = settings.level
        set(value) = synchronized(this) { settings = Settings(value, settings.handler) }

    var handler: ((Reception.LogLevel, String) -> Unit)?
        get() = settings.handler
        set(value) = synchronized(this) { settings = Settings(settings.level, value) }

    fun error(message: () -> String) {
        emit(Reception.LogLevel.ERROR, message)
    }

    fun info(message: () -> String) {
        emit(Reception.LogLevel.INFO, message)
    }

    fun debug(message: () -> String) {
        emit(Reception.LogLevel.DEBUG, message)
    }

    fun redacted(secret: String): String = "<${secret.length} chars>"

    private fun emit(level: Reception.LogLevel, message: () -> String) {
        val current = settings
        if (current.level == Reception.LogLevel.OFF || level > current.level) return
        val line = message()
        val handler = current.handler
        if (handler != null) {
            handler(level, line)
            return
        }
        when (level) {
            Reception.LogLevel.ERROR -> AndroidLog.e(TAG, "[Reception] ERROR $line")
            Reception.LogLevel.INFO -> AndroidLog.i(TAG, "[Reception] INFO $line")
            Reception.LogLevel.DEBUG -> AndroidLog.d(TAG, "[Reception] DEBUG $line")
            Reception.LogLevel.OFF -> Unit
        }
    }
}
