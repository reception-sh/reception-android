package sh.reception.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.reception.ReceptionState
import sh.reception.model.Attachment
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** What a photo view shows: a placeholder, a spinner, the photo, or a Retry action. */
internal sealed interface AttachmentImageState {
    data object Idle : AttachmentImageState
    data object Loading : AttachmentImageState
    class Loaded(val bitmap: Bitmap) : AttachmentImageState
    data object Failed : AttachmentImageState
}

/**
 * One attachment's image for every view that shows it (bubble and lightbox), scoped to its device session. The
 * supplied URL comes first; only a storage `403` triggers one authenticated resolve and one more download per attempt.
 * Any other failure, invalid data, a failed resolve or a second `403` shows Retry, which resolves before downloading.
 * Main thread only.
 */
internal class AttachmentImageLoader(
    private var attachment: Attachment,
    session: DeviceSession,
    private val download: suspend (String) -> ByteArray = ::downloadImage,
    private val decode: (ByteArray) -> Bitmap? = { BitmapFactory.decodeByteArray(it, 0, it.size) },
    private val isCurrent: (DeviceSession) -> Boolean = { it === ReceptionState.session && !it.invalidated },
) {
    private val mutableState = MutableStateFlow<AttachmentImageState>(AttachmentImageState.Idle)
    val state: StateFlow<AttachmentImageState> = mutableState.asStateFlow()
    private val session = WeakReference(session)
    private var task: Job? = null
    private var attempt = 0
    private var readers = 0

    private val current: Boolean
        get() {
            val session = session.get() ?: return false
            return isCurrent(session)
        }

    fun appear() {
        readers += 1
        val state = mutableState.value
        if (state !is AttachmentImageState.Loaded && state != AttachmentImageState.Failed) load(refresh = false)
    }

    /** The last disappearing view cancels the load. */
    fun disappear() {
        readers = maxOf(0, readers - 1)
        if (readers == 0) cancel()
    }

    fun retry() = load(refresh = true)

    fun invalidate() {
        cancel()
        mutableState.value = AttachmentImageState.Idle
    }

    private fun cancel() {
        attempt += 1
        task?.cancel()
        task = null
        if (mutableState.value == AttachmentImageState.Loading) mutableState.value = AttachmentImageState.Idle
    }

    private fun load(refresh: Boolean) {
        val session = session.get()
        if (!current || task != null || session == null) return
        val id = ++attempt
        mutableState.value = AttachmentImageState.Loading
        lateinit var job: Job
        job = session.scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (refresh) resolve()
                val data = try {
                    download(attachment.url)
                } catch (_: Forbidden) {
                    if (refresh) throw Unavailable()
                    checkCurrent()
                    resolve()
                    download(attachment.url)
                }
                checkCurrent()
                val bitmap = withContext(Dispatchers.Default) { decode(data) } ?: throw Unavailable()
                checkCurrent()
                if (attempt == id) mutableState.value = AttachmentImageState.Loaded(bitmap)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (current && attempt == id) mutableState.value = AttachmentImageState.Failed
            } finally {
                if (attempt == id) {
                    task = null
                    if (mutableState.value == AttachmentImageState.Loading) mutableState.value = AttachmentImageState.Idle
                }
            }
        }
        task = job
        job.start()
    }

    private fun checkCurrent() {
        if (!current) throw NotCurrent()
    }

    private suspend fun resolve() {
        checkCurrent()
        val session = session.get()
        if (session == null || !session.isRegistered) throw NotCurrent()
        // The ID is one path component, never a caller-supplied endpoint or query.
        val id = attachment.id.takeIf { it.isNotEmpty() && it.all { char -> char in allowed } } ?: throw Unavailable()
        try {
            val resolved = session.api.request<Attachment>("attachments/$id", authenticated = true)
            checkCurrent()
            if (resolved.id != attachment.id) throw Unavailable()
            attachment = resolved
        } catch (error: ReceptionAPIError) {
            checkCurrent()
            // Only resolver errors carry device authentication. Storage errors never reset support.
            ReceptionState.handleReset(session, error)
            throw error
        }
    }

    private class Forbidden : Exception()

    private class Unavailable : Exception()

    private class NotCurrent : Exception()

    companion object {
        private const val MAXIMUM_IMAGE_BYTES = 20L * 1024 * 1024
        private val allowed = ('a'..'z').toSet() + ('A'..'Z') + ('0'..'9') + setOf('-', '_', '.', '~')
        private val storageClient: OkHttpClient by lazy {
            HttpExchange.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        }

        /** A storage download: no SDK headers, `403` reported apart from every other failure. */
        private suspend fun downloadImage(url: String): ByteArray {
            val parsed = url.toHttpUrlOrNull() ?: throw Unavailable()
            val reply = try {
                HttpExchange.data(Request.Builder().url(parsed).build(), storageClient, MAXIMUM_IMAGE_BYTES)
            } catch (_: IOException) {
                throw Unavailable()
            }
            ReceptionAPI.logRateLimit(reply.status, reply.headers)
            if (reply.status == 403) throw Forbidden()
            if (reply.status !in 200..299) throw Unavailable()
            return reply.body
        }
    }
}
