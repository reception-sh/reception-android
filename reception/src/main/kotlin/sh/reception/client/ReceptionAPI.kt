package sh.reception.client

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import sh.reception.Log
import sh.reception.SDKVersion
import sh.reception.model.EmptyResponse
import java.io.IOException

/**
 * The service's `/v1` API for one App ID. A session binds its own copy ([bound]), so an old session's requests never
 * use a newer session's credentials.
 */
internal class ReceptionAPI(
    val appId: String,
    val baseUrl: HttpUrl = serviceUrl,
    val session: DeviceSession? = null,
) {
    fun bound(session: DeviceSession): ReceptionAPI = ReceptionAPI(appId, baseUrl, session)

    /** The support revision the session's store holds, sent with every request. */
    private val chatRevision: Int
        get() = session?.chatRevision ?: 0

    /**
     * `authenticated` requests carry the session's access token. A rejected token is replaced once and the request
     * retried once; callers never hold tokens themselves. Known cooldowns are checked before the token and again
     * right before sending, so a wait that started while this request was suspended still applies.
     */
    suspend fun <Response> request(
        path: String,
        response: DeserializationStrategy<Response>,
        method: String = "GET",
        authenticated: Boolean = false,
        body: String? = null,
        expectedStatus: Int? = null,
        photos: Boolean = false,
    ): Response {
        val scopes = RetryScope.gates(path, method, authenticated, photos)
        session?.prepared()
        session?.admit(scopes)
        val token = if (authenticated) activeSession().token() else null
        val probe = session?.authorizeRequest(scopes) ?: false
        try {
            val value = try {
                send(path, response, method, token, body, expectedStatus)
            } catch (error: ReceptionAPIError) {
                if (token == null || !error.rejectsToken) throw error
                val renewed = activeSession().renewedToken(replacing = token)
                session?.admit(scopes)
                send(path, response, method, renewed, body, expectedStatus)
            }
            session?.requestSucceeded(probe)
            return value
        } catch (error: ReceptionAPIError) {
            session?.requestFailed(error, path, method)
            throw error
        }
    }

    suspend inline fun <reified Response> request(
        path: String,
        method: String = "GET",
        authenticated: Boolean = false,
        body: String? = null,
        expectedStatus: Int? = null,
        photos: Boolean = false,
    ): Response = request(path, serializer<Response>(), method, authenticated, body, expectedStatus, photos)

    /** One HTTP exchange without session bookkeeping. */
    suspend fun <Response> send(
        path: String,
        response: DeserializationStrategy<Response>,
        method: String,
        token: String?,
        body: String?,
        expectedStatus: Int? = null,
    ): Response {
        val request = httpRequest(path, token).method(method, requestBody(method, body)).build()
        val reply = try {
            HttpExchange.send(request)
        } catch (error: IOException) {
            throw ReceptionAPIError("connection_failed", 0, networkError = error.javaClass.simpleName)
        }
        val accepted = expectedStatus?.let { reply.status == it } ?: (reply.status in 200..299)
        if (!accepted) {
            logRateLimit(reply.status, reply.headers)
            val envelope = ErrorEnvelope.parse(json, reply.body)
            val wait = wait(reply.status, reply.headers["Retry-After"], envelope?.retryScope, path, method, token != null)
            throw ReceptionAPIError(envelope?.code ?: "http_error", reply.status, envelope?.message.orEmpty(),
                envelope?.resetRevision, retryAfter = wait.seconds, retryScope = wait.scope)
        }
        return try {
            json.decodeFromString(response, reply.body)
        } catch (_: SerializationException) {
            throw ReceptionAPIError("invalid_response", reply.status)
        } catch (_: IllegalArgumentException) {
            throw ReceptionAPIError("invalid_response", reply.status)
        }
    }

    suspend fun deleteDevice(sessionSecret: String) {
        request<EmptyResponse>("devices/me", "DELETE", body = encode(SessionSecretInput(sessionSecret)),
            expectedStatus = 200)
    }

    private fun activeSession(): DeviceSession = session ?: throw ReceptionAPIError("cancelled", 0)

    fun httpRequest(path: String, token: String?): Request.Builder {
        val endpoint = path.substringBefore('?')
        val query = path.substringAfter('?', missingDelimiterValue = "").takeIf { '?' in path }
        val basePath = baseUrl.pathSegments.filter { it.isNotEmpty() }.joinToString("") { "/$it" }
        val url = try {
            baseUrl.newBuilder().encodedPath("$basePath/v1/$endpoint").encodedQuery(query).build()
        } catch (_: IllegalArgumentException) {
            throw ReceptionAPIError("invalid_url", 0)
        }
        val builder = Request.Builder().url(url)
            .header("X-Reception-Revision", chatRevision.toString())
            .header("X-App-Id", appId)
            .header("Content-Type", "application/json")
            .header("User-Agent", "Reception-Android/${SDKVersion.current}")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder
    }

    // OkHttp needs a body for POST, PUT and PATCH and refuses one for GET.
    private fun requestBody(method: String, body: String?) = when {
        body != null -> body.toRequestBody(jsonType)
        method == "GET" || method == "HEAD" -> null
        else -> ByteArray(0).toRequestBody(null)
    }

    @Serializable
    private class SessionSecretInput(val sessionSecret: String)

    companion object {
        /** The service never asks for more than a day; a longer value is capped rather than trusted. */
        const val MAXIMUM_RETRY_AFTER: Int = 86_400

        val defaultServiceUrl: HttpUrl = "https://reception.sh".toHttpUrl()

        /** The hosted Reception service. Only Reception's own debuggable test apps point it elsewhere. */
        @Volatile
        var serviceUrl: HttpUrl = defaultServiceUrl

        val json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private val jsonType = "application/json".toMediaType()
        private val appIdPattern = Regex("^app_[A-Za-z0-9]{22}$")

        fun accepts(appId: String): Boolean = appIdPattern.matches(appId)

        inline fun <reified Value> encode(value: Value): String = try {
            json.encodeToString(serializer<Value>(), value)
        } catch (_: SerializationException) {
            throw ReceptionAPIError("invalid_input", 0)
        }

        fun logRateLimit(status: Int, headers: Headers) {
            if (status != 429) return
            val seconds = retryAfter(status, headers["Retry-After"]) ?: 0
            Log.debug { "Rate limited, HTTP 429, retry after ${seconds}s" }
        }
    }
}
