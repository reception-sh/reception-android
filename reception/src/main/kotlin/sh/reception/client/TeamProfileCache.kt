package sh.reception.client

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import sh.reception.model.TeamMember
import java.io.File
import java.io.IOException

/** Small, session-scoped team names and photos without signed download URLs, in `noBackupFilesDir`. */
internal class TeamProfileCache(context: Context, scope: String) {
    class Entry(val name: String? = null, val photoId: String? = null, val data: ByteArray? = null)

    @Serializable
    private class Stored(val name: String? = null, val photoId: String? = null)

    private val directory = CacheFiles.directory(context, scope, "team")
    private val file = File(directory, "team.json")
    private var entries = mutableMapOf<String, Entry>()
    private var acceptsWrites = true

    fun load(): Map<String, Entry> {
        val stored = try {
            if (!file.isFile || file.length() > MAXIMUM_INDEX_BYTES) return emptyMap()
            ReceptionAPI.json.decodeFromString(serializer, file.readText())
        } catch (_: IOException) {
            return emptyMap()
        } catch (_: SerializationException) {
            return emptyMap()
        } catch (_: IllegalArgumentException) {
            return emptyMap()
        }
        if (stored.size > MAXIMUM_MEMBERS) return emptyMap()
        entries = stored.mapValuesTo(mutableMapOf()) { (id, value) ->
            Entry(value.name, value.photoId, value.photoId?.let { readPhoto(id) })
        }
        return entries.toMap()
    }

    fun save(data: ByteArray, photoId: String, memberId: String) {
        val others = entries.filterKeys { it != memberId }.values.sumOf { it.data?.size ?: 0 }
        if (!acceptsWrites || data.size > MAXIMUM_PHOTO_BYTES || others + data.size > MAXIMUM_TOTAL_BYTES ||
            (memberId !in entries && entries.size >= MAXIMUM_MEMBERS)
        ) return
        entries[memberId] = Entry(entries[memberId]?.name, photoId, data)
        persist { CacheFiles.write(photoFile(memberId), data) }
    }

    fun update(team: Map<String, TeamMember>, showsPhotos: Boolean) {
        if (!acceptsWrites) return
        val updated = mutableMapOf<String, Entry>()
        for (id in team.keys.sorted().take(MAXIMUM_MEMBERS)) {
            val member = team[id] ?: continue
            val photo = if (showsPhotos && member.photoId != null) entries[id] else null
            updated[id] = Entry(member.name, photo?.photoId, photo?.data)
        }
        val same = updated.keys == entries.keys && updated.all { (id, entry) ->
            val current = entries[id]
            current != null && current.name == entry.name && current.photoId == entry.photoId && current.data === entry.data
        }
        if (same) return
        entries = updated
        persist {}
    }

    fun clear() {
        // A late download from the discarded session cannot recreate deleted files.
        acceptsWrites = false
        entries.clear()
        directory.deleteRecursively()
    }

    /** Runs [writePhoto], then drops photos no entry holds and rewrites the index. */
    private fun persist(writePhoto: () -> Unit) {
        if (entries.isEmpty()) {
            directory.deleteRecursively()
            return
        }
        try {
            writePhoto()
            val names = entries.filterValues { it.data != null }.keys.map { photoFile(it).name }.toSet()
            directory.listFiles()?.filter { it.name.endsWith(".photo") && it.name !in names }?.forEach { it.delete() }
            val stored = entries.mapValues { Stored(it.value.name, it.value.photoId) }
            CacheFiles.write(file, ReceptionAPI.json.encodeToString(serializer, stored).toByteArray())
        } catch (_: IOException) {
            // Disk availability must not prevent displaying the downloaded photo.
        }
    }

    private fun readPhoto(memberId: String): ByteArray? = try {
        photoFile(memberId).takeIf { it.isFile && it.length() <= MAXIMUM_PHOTO_BYTES }?.readBytes()
    } catch (_: IOException) {
        null
    }

    private fun photoFile(memberId: String): File = File(directory, "${CacheFiles.hash(memberId).take(32)}.photo")

    private companion object {
        const val MAXIMUM_MEMBERS = 50
        const val MAXIMUM_PHOTO_BYTES = 512 * 1024
        const val MAXIMUM_TOTAL_BYTES = 6 * 1024 * 1024
        const val MAXIMUM_INDEX_BYTES = 256 * 1024L
        val serializer = MapSerializer(String.serializer(), Stored.serializer())
    }
}
