package sh.reception.client

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import sh.reception.MainThread

/**
 * The upload body: exactly [data], reporting the fraction written on the main thread. It declares no content type,
 * so OkHttp keeps the signed `Content-Type` header the reservation returned.
 */
internal class UploadProgress(private val data: ByteArray, private val update: (Double) -> Unit) : RequestBody() {
    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = data.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var written = 0
        while (written < data.size) {
            val count = minOf(CHUNK_BYTES, data.size - written)
            sink.write(data, written, count)
            sink.flush()
            written += count
            val fraction = written.toDouble() / data.size
            MainThread.run { update(fraction) }
        }
    }

    private companion object {
        const val CHUNK_BYTES = 16 * 1024
    }
}
