package sh.reception.client

import okhttp3.OkHttpClient
import sh.reception.configuration.AppearanceCache
import sh.reception.configuration.AppearanceEnvelope
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A published appearance envelope and its JSON text for the cache (both null for `304`), and its ETag. */
internal class AppearanceReply(val envelope: AppearanceEnvelope?, val body: String?, val etag: String?)

private val appearanceClient: OkHttpClient by lazy {
    HttpExchange.client.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
}

/** `GET /v1/appearance?platform=android`, so the push prompt follows this platform's credentials. */
internal suspend fun ReceptionAPI.appearance(etag: String?): AppearanceReply {
    session?.authorizeRequest()
    val builder = httpRequest("appearance?platform=android", token = null)
    if (etag != null) builder.header("If-None-Match", etag)
    val reply = try {
        HttpExchange.send(builder.get().build(), appearanceClient, limit = MAXIMUM_APPEARANCE_BYTES)
    } catch (_: BodyTooLargeException) {
        throw ReceptionAPIError("appearance_too_large", 200)
    } catch (error: IOException) {
        throw ReceptionAPIError("connection_failed", 0, networkError = error.javaClass.simpleName)
    }
    if (reply.status == 304 && etag != null) return AppearanceReply(null, null, reply.headers["ETag"])
    if (reply.status != 200) {
        ReceptionAPI.logRateLimit(reply)
        val envelope = ErrorEnvelope.parse(ReceptionAPI.json, reply.body)
        throw ReceptionAPIError(envelope?.code ?: "appearance_unavailable", reply.status,
            retryAfter = ReceptionAPI.retryAfter(reply.status, reply.headers["Retry-After"]))
    }
    val envelope = AppearanceCache.decode(reply.body, requireCompatible = false)
        ?: throw ReceptionAPIError("invalid_appearance", 200)
    return AppearanceReply(envelope, reply.body, reply.headers["ETag"])
}

/** The largest appearance envelope the SDK reads or caches. */
internal const val MAXIMUM_APPEARANCE_BYTES: Long = 65_536
