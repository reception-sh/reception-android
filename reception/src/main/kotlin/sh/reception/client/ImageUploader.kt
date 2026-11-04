package sh.reception.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Prepares a photo, reserves its upload and puts the bytes to storage. Returns the attachment ID. */
internal object ImageUploader {
    private val storageClient: OkHttpClient by lazy {
        HttpExchange.client.newBuilder().writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    @Serializable
    private class Input(
        val requestId: String,
        val contentType: String,
        val bytes: Int,
        val width: Int,
        val height: Int,
        val checksum: String,
    )

    @Serializable
    private class Output(val attachmentId: String, val uploadUrl: String, val headers: Map<String, String>)

    suspend fun upload(image: ByteArray, messageId: String, index: Int, session: DeviceSession, progress: (Double) -> Unit): String {
        val jpeg = withContext(Dispatchers.Default) { ImagePreparation.prepare(image) }
        val checksum = jpeg.data.toByteString().sha256().base64()
        val input = Input(requestId(messageId, index, checksum), "image/jpeg", jpeg.data.size, jpeg.width, jpeg.height, checksum)
        val output = session.api.request<Output>("uploads", "POST", authenticated = true, body = ReceptionAPI.encode(input))
        val url = output.uploadUrl.toHttpUrlOrNull() ?: throw ReceptionAPIError("invalid_upload_url", 0)
        // The storage signature covers these headers, including If-Match, so they go out exactly as returned.
        val request = try {
            Request.Builder().url(url).headers(output.headers.toHeaders()).put(UploadProgress(jpeg.data, progress)).build()
        } catch (_: IllegalArgumentException) {
            throw ReceptionAPIError("upload_failed", 0)
        }
        val reply = try {
            HttpExchange.send(request, storageClient)
        } catch (error: IOException) {
            throw ReceptionAPIError("upload_failed", 0, networkError = error.javaClass.simpleName)
        }
        ReceptionAPI.logRateLimit(reply.status, reply.headers)
        if (reply.status == 412 && output.headers.keys.any { it.equals("If-Match", ignoreCase = true) }) {
            // A successful PUT whose response was lost fails its conditional retry; the message POST checks the bytes.
            return output.attachmentId
        }
        if (reply.status !in 200..299) {
            // Storage replies never start a Reception cooldown; their wait only shapes the ordinary backoff.
            throw ReceptionAPIError("upload_failed", reply.status,
                retryAfter = ReceptionAPI.retryAfter(reply.status, reply.headers["Retry-After"], maximum = 300))
        }
        return output.attachmentId
    }

    /** Every retry of the same photo reuses its reservation: a UUID derived from the message, position and bytes. */
    fun requestId(messageId: String, index: Int, checksum: String): String {
        val bytes = "$messageId:$index:$checksum".encodeUtf8().sha256().toByteArray().copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val most = bytes.take(8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
        val least = bytes.drop(8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
        return UUID(most, least).toString().uppercase()
    }
}
