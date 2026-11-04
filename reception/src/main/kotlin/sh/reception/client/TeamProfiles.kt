package sh.reception.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
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
import sh.reception.model.TeamMember
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Team names and photos: restored together from [cache], then refreshed from authoritative team details. Photos are
 * held in memory by member, keyed by their photo ID; a replacement downloads while the previous photo stays visible.
 * A failed download is not retried until the next update. Main thread only, the cache on [Storage]; [scope] ends
 * every download.
 */
internal class TeamProfiles(
    private val scope: CoroutineScope,
    private val cache: TeamProfileCache? = null,
    private val download: suspend (String) -> ByteArray? = ::downloadPhoto,
    private val decode: (ByteArray) -> Bitmap? = { BitmapFactory.decodeByteArray(it, 0, it.size) },
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

    init {
        // The cache loads on the storage thread; team details that arrived meanwhile are newer and win.
        if (cache != null) scope.launch { restore(Storage.run { cache.load() }) }
    }

    private suspend fun restore(entries: Map<String, TeamProfileCache.Entry>) {
        val members = mutableMapOf<String, TeamMember>()
        val images = mutableMapOf<String, Bitmap>()
        for ((id, entry) in entries) {
            entry.name?.let { members[id] = TeamMember(id, it, photoId = entry.photoId) }
            entry.data?.let { data -> withContext(Dispatchers.Default) { decode(data) } }?.let { images[id] = it }
        }
        if (updated) return
        mutableMembers.value = members
        for ((id, image) in images) {
            if (id in mutableImages.value || id in pending) continue
            mutableImages.value += id to image
            loadedKeys[id] = entries[id]?.photoId
        }
    }

    fun update(team: Map<String, TeamMember>, showsPhotos: Boolean = true) {
        updated = true
        mutableMembers.value = team
        cache?.let { Storage.launch { it.update(team, showsPhotos) } }
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
            val image = data?.let { withContext(Dispatchers.Default) { decode(it) } }
            if (pending[id]?.attempt != attempt) return
            pending.remove(id)
            if (data == null || image == null) return
            mutableImages.value += id to image
            loadedKeys[id] = key
            val photoId = member.photoId
            if (photoId != null && cache != null) Storage.launch { cache.save(data, photoId, id) }
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
