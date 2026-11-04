package sh.reception.client

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A response body above the caller's limit; never read into memory. */
internal class BodyTooLargeException : IOException("body_too_large")

/** A completed HTTP exchange; the body is read on OkHttp's thread, never on the main thread. */
internal class HttpReply(val status: Int, val headers: okhttp3.Headers, val body: String)

/** A completed download, such as an image from storage. */
internal class HttpData(val status: Int, val headers: okhttp3.Headers, val body: ByteArray)

internal object HttpExchange {
    /** One client for the process, so every request shares its connection pool. No cache: the SDK caches itself. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Sends [request]; cancelling the caller cancels the call. Bodies over [limit] bytes fail with [BodyTooLargeException]. */
    suspend fun send(request: Request, client: OkHttpClient = this.client, limit: Long = Long.MAX_VALUE): HttpReply =
        exchange(request, client, limit) { HttpReply(it.code, it.headers, it.body?.string().orEmpty()) }

    /** [send] for binary bodies. */
    suspend fun data(request: Request, client: OkHttpClient = this.client, limit: Long = Long.MAX_VALUE): HttpData =
        exchange(request, client, limit) { HttpData(it.code, it.headers, it.body?.bytes() ?: ByteArray(0)) }

    private suspend fun <Reply> exchange(request: Request, client: OkHttpClient, limit: Long, read: (Response) -> Reply): Reply =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use {
                            val source = it.body?.source()
                            if (source != null && limit < Long.MAX_VALUE && source.request(limit + 1)) {
                                throw BodyTooLargeException()
                            }
                            read(it)
                        }
                    } catch (error: IOException) {
                        continuation.resumeWithException(error)
                        return
                    }
                    continuation.resume(reply)
                }
            })
        }
}
