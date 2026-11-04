package sh.reception.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.reception.model.TeamMember
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Team names and photos: restored together from [cache], then refreshed from authoritative team details. Photos are
 * held in memory by member, keyed by their photo ID; a replacement downloads and decodes while the previous photo
 * stays visible. A failed download is not retried until the next update. State changes on [scope]'s dispatcher (the
 * main thread); files are read and written on [storage], one at a time, and photos decode on [decoding]. [scope] ends
 * every download.
 */
internal class TeamProfiles(
    private val scope: CoroutineScope,
    private val cache: TeamProfileCache? = null,
    private val download: suspend (String) -> ByteArray? = ::downloadPhoto,
    private val decode: (ByteArray) -> Bitmap? = { BitmapFactory.decodeByteArray(it, 0, it.size) },
    private val storage: CoroutineDispatcher = Storage.dispatcher,
    private val decoding: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Pending(val key: String, val url: String, val attempt: Int, val job: Job)

    private val mutableMembers = MutableStateFlow<Map<String, TeamMember>>(emptyMap())
    private val mutableImages = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val members: StateFlow<Map<String, TeamMember>> = mutableMembers.asStateFlow()
    val images: StateFlow<Map<String, Bitmap>> = mutableImages.asStateFlow()
    private val loadedKeys = mutableMapOf<String, String?>()
    private val pending = mutableMapOf<String, Pending>()
    private var attempts = 0
    private var updated = false
    private var showsPhotos = true
    private var lastWrite: Job? = null

    // On [storage], independent of [scope] like the writes: a cancelled caller must not leave a write without the
    // entries it keeps.
    private val loading: Deferred<Map<String, TeamProfileCache.Entry>>? =
        cache?.let { cache -> scope.async(storage + NonCancellable) { cache.load() } }

    init {
        loading?.let { loading ->
            lastWrite = loading
            scope.launch { restore(loading.await()) }
        }
    }

    private suspend fun restore(entries: Map<String, TeamProfileCache.Entry>) {
        val images = withContext(decoding) {
            entries.mapNotNull { (id, entry) -> entry.data?.let(decode)?.let { id to it } }.toMap()
        }
        // Team details that arrived meanwhile are newer: they keep their names, and a cached photo is used only when it
        // is the photo they name, whatever happened to its download.
        if (!updated) {
            mutableMembers.value = entries.mapNotNull { (id, entry) ->
                entry.name?.let { id to TeamMember(id, it, photoId = entry.photoId) }
            }.toMap()
        }
        for ((id, image) in images) {
            val photoId = entries[id]?.photoId
            val wanted = if (updated) {
                showsPhotos && photoId != null && mutableMembers.value[id]?.photoId == photoId && loadedKeys[id] != photoId
            } else {
                id !in mutableImages.value
            }
            if (!wanted) continue
            pending.remove(id)?.job?.cancel()
            mutableImages.value += id to image
            loadedKeys[id] = photoId
        }
    }

    /**
     * Cache writes run in order on [storage], independent of [scope], so a late write is never lost or reordered. They
     * need the loaded entries: without them a write would replace the index and delete the cached photos.
     */
    private fun write(block: (TeamProfileCache) -> Unit) {
        val cache = cache ?: return
        val previous = lastWrite
        lastWrite = scope.launch(storage + NonCancellable) {
            previous?.join()
            if (loading?.isCancelled == true) return@launch
            block(cache)
        }
    }

    fun update(team: Map<String, TeamMember>, showsPhotos: Boolean = true) {
        updated = true
        this.showsPhotos = showsPhotos
        mutableMembers.value = team
        write { it.update(team, showsPhotos) }
        val photos = if (showsPhotos) team else emptyMap()
        for (id in mutableImages.value.keys + pending.keys) {
            if (photos[id]?.photoId != null || photos[id]?.photoUrl != null) continue
            pending.remove(id)?.job?.cancel()
            mutableImages.value -= id
            loadedKeys.remove(id)
        }
        for ((id, member) in photos) {
            val url = member.photoUrl ?: continue
            // Older servers have no photo ID; the full URL remains a safe fallback.
            val key = member.photoId ?: url
            if (loadedKeys[id] == key) {
                pending.remove(id)?.job?.cancel()
                continue
            }
            if (pending[id]?.key == key && pending[id]?.url == url) continue
            pending.remove(id)?.job?.cancel()
            val attempt = ++attempts
            val job = scope.launch(start = CoroutineStart.LAZY) { load(id, member, url, key, attempt) }
            pending[id] = Pending(key, url, attempt, job)
            job.start()
        }
    }

    private suspend fun load(id: String, member: TeamMember, url: String, key: String, attempt: Int) {
        try {
            val data = download(url)
            if (pending[id]?.attempt != attempt) return
            val image = data?.let { withContext(decoding) { decode(it) } }
            if (pending[id]?.attempt != attempt) return
            pending.remove(id)
            if (data == null || image == null) return
            mutableImages.value += id to image
            loadedKeys[id] = key
            val photoId = member.photoId
            if (photoId != null) write { it.save(data, photoId, id) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            if (pending[id]?.attempt == attempt) pending.remove(id)
        }
    }

    private companion object {
        const val MAXIMUM_PHOTO_BYTES = 5L * 1024 * 1024
        val photoClient: OkHttpClient by lazy { HttpExchange.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build() }

        /** The photo's bytes, or null for any answer but a success. */
        suspend fun downloadPhoto(url: String): ByteArray? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val reply = HttpExchange.data(Request.Builder().url(parsed).build(), photoClient, MAXIMUM_PHOTO_BYTES)
            return reply.body.takeIf { reply.status in 200..299 }
        }
    }
}
