package sh.reception.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class ReceptionAPIError(
    val code: String,
    val status: Int,
    val serverMessage: String = "",
    val resetRevision: Int? = null,
    /** The transport failure's class name, for debug logs only. */
    val networkError: String? = null,
    val retryAfter: Int? = null,
    /** Set when the service asked this device to wait before repeating work of that scope. */
    val retryScope: RetryScope? = null,
) : Exception(code) {
    /** A known cooldown kept this request off the network, so it cost no attempt. */
    val isCooldown: Boolean
        get() = code == "cooldown"

    /** The only response that replaces an access token; `session_revoked` comes from the refresh itself. */
    val rejectsToken: Boolean
        get() = status == 401 && code == "unauthorized"

    /** Identity token failures from a registration or device update; they never end the session itself. */
    val rejectsIdentityToken: Boolean
        get() = code in setOf("identity_token_invalid", "identity_token_expired", "identity_not_configured",
            "identity_mismatch")

    companion object {
        fun isTransient(error: Throwable): Boolean = when (error) {
            is CancellationException -> false
            is ReceptionAPIError -> error.code != "cancelled" &&
                (error.networkError != null || error.status in 500..599 || error.status == 408 || error.status == 429)
            is IOException -> true
            else -> false
        }
    }
}

/** A server wait: how long and for which limit. */
internal data class ServerWait(val seconds: Int?, val scope: RetryScope?)

/** The service's `{ error: { code, message, resetRevision?, retryScope? } }`. */
internal data class ErrorEnvelope(val code: String, val message: String, val resetRevision: Int?, val retryScope: String?) {
    companion object {
        fun parse(json: Json, text: String): ErrorEnvelope? {
            val detail = try {
                json.parseToJsonElement(text).jsonObject["error"] as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            val code = (detail["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val message = (detail["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val revision = detail["resetRevision"]
            val resetRevision = when (revision) {
                null, JsonNull -> null
                else -> (revision as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
            }
            // A malformed scope from a newer service must not hide the error code.
            val scope = (detail["retryScope"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return ErrorEnvelope(code, message, resetRevision, scope)
        }
    }
}

/** Whole seconds from a `429` response's `Retry-After`. HTTP dates and negative values count as missing. */
internal fun ReceptionAPI.Companion.retryAfter(status: Int, header: String?, maximum: Int = MAXIMUM_RETRY_AFTER): Int? =
    if (status == 429) headerSeconds(header, maximum) else null

/**
 * A `429` waits for the scope the service names, else its route's limit or the request budget. A `503` waits only
 * when the service names a scope; every other `503` remains an ordinary transient failure with backoff.
 */
internal fun ReceptionAPI.Companion.wait(
    status: Int,
    retryAfterHeader: String?,
    named: String?,
    path: String,
    method: String,
    authenticated: Boolean,
): ServerWait {
    val scope = RetryScope.from(named)
    return when (status) {
        429 -> {
            val seconds = retryAfter(status, retryAfterHeader)
            if (seconds == null || RetryScope.exempt(path, method)) return ServerWait(seconds, null)
            ServerWait(seconds, scope ?: RetryScope.route(path, method) ?: if (authenticated) RetryScope.REQUESTS else null)
        }
        503 -> {
            val seconds = headerSeconds(retryAfterHeader, MAXIMUM_RETRY_AFTER)
            if (scope == null || seconds == null || RetryScope.exempt(path, method)) ServerWait(null, null)
            else ServerWait(seconds, scope)
        }
        else -> ServerWait(null, null)
    }
}

/** At least one second, so a `0` never turns into back-to-back retries. */
private fun headerSeconds(header: String?, maximum: Int): Int? {
    val seconds = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return seconds.coerceIn(1, maximum.toLong()).toInt()
}
