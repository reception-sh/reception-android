package sh.reception.client

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import sh.reception.model.Attachment
import sh.reception.model.Message
import sh.reception.model.MessageStatus
import sh.reception.model.Timestamp
import java.io.File
import java.io.IOException

/**
 * A per-session snapshot of the last 50 confirmed messages plus pending and failed ones, with their unconfirmed
 * photos as files. It lives in `noBackupFilesDir`, so backups never carry it; pending messages keep their client IDs
 * across launches. Everything here reads or writes files, so it runs on [Storage]; only [latestMessageAt], a memory
 * value that [restoreLatestMessageAt] fills when the store opens, may be read on the main thread.
 */
internal class ChatCache(context: Context, scope: String) {
    private val directory = CacheFiles.directory(context, scope, "chat")
    private val file = File(directory, "messages.json")
    private val photos = File(directory, "photos")
    @Volatile
    private var cachedLatestMessageAt: Long? = null

    /** Messages whose photos are already on disk, by ID, so a save does not write them again. */
    private val storedImages = mutableMapOf<String, Int>()

    /** Memory only: never reads the disk, so the main thread may ask. */
    val latestMessageAt: Long?
        get() = cachedLatestMessageAt

    /** Storage thread. Reads the newest message time from disk; the store calls it on every open. */
    fun restoreLatestMessageAt() {
        rememberLatestMessageAt(readSnapshots())
    }

    fun load(): List<Message> {
        val snapshots = readSnapshots()
        rememberLatestMessageAt(snapshots)
        storedImages.clear()
        return snapshots.map { snapshot ->
            val images = if (snapshot.hasConfirmedAttachments) emptyList() else readImages(snapshot)
            if (images.isNotEmpty()) storedImages[snapshot.id] = images.size
            snapshot.message(images)
        }
    }

    fun save(messages: List<Message>) {
        val recent = messages.filter { it.seq != null }.takeLast(50).map { it.id }.toSet()
        val retained = messages.filter { it.seq == null || it.id in recent }
        try {
            CacheFiles.prepare(photos)
            val keep = mutableSetOf<String>()
            for (message in retained) {
                if (message.hasConfirmedAttachments || message.images.isEmpty()) continue
                val names = message.images.indices.map { imageName(message.id, it) }
                if (storedImages[message.id] != message.images.size) {
                    message.images.forEachIndexed { index, data -> CacheFiles.write(File(photos, names[index]), data) }
                    storedImages[message.id] = message.images.size
                }
                keep.addAll(names)
            }
            storedImages.keys.retainAll(retained.filter { !it.hasConfirmedAttachments && it.images.isNotEmpty() }
                .map { it.id }.toSet())
            photos.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            val snapshots = retained.map(::Snapshot)
            CacheFiles.write(file, ReceptionAPI.json.encodeToString(serializer, snapshots).toByteArray())
            rememberLatestMessageAt(snapshots)
        } catch (_: IOException) {
            // Disk availability must never prevent sending a support message.
        }
    }

    fun clear() {
        rememberLatestMessageAt(emptyList())
        storedImages.clear()
        directory.deleteRecursively()
    }

    private fun rememberLatestMessageAt(snapshots: List<Snapshot>) {
        cachedLatestMessageAt = snapshots.maxOfOrNull { it.createdAt }
    }

    private fun readSnapshots(): List<Snapshot> = try {
        if (!file.isFile) emptyList() else ReceptionAPI.json.decodeFromString(serializer, file.readText())
    } catch (_: IOException) {
        emptyList()
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    // A photo that cannot be read is left out; `expectedImageCount` keeps the message from sending without it.
    private fun readImages(snapshot: Snapshot): List<ByteArray> = (0 until snapshot.imageCount).mapNotNull { index ->
        try {
            File(photos, imageName(snapshot.id, index)).readBytes()
        } catch (_: IOException) {
            null
        }
    }

    private fun imageName(messageId: String, index: Int): String = "${CacheFiles.hash(messageId).take(32)}-$index.jpg"

    @Serializable
    private class Snapshot(
        val id: String,
        val seq: Long? = null,
        val sender: String,
        val text: String,
        val kind: String = "TEXT",
        val reviewUrl: String? = null,
        val linkUrl: String? = null,
        val paywallId: String? = null,
        val paywallTitle: String? = null,
        val buttonLabel: String? = null,
        val authorId: String? = null,
        @Serializable(with = Timestamp::class) val actionClickedAt: Long? = null,
        @Serializable(with = Timestamp::class) val createdAt: Long,
        val failed: Boolean,
        val awaitsManualRetry: Boolean = false,
        val retryAfterLimitRelease: Boolean = false,
        val retryAfterTransientFailure: Boolean = false,
        val attachments: List<Attachment>,
        val imageCount: Int = 0,
        val attachmentIds: List<String> = emptyList(),
        val expectedImageCount: Int? = null,
    ) {
        val hasConfirmedAttachments: Boolean
            get() = seq != null && attachments.isNotEmpty()

        constructor(message: Message) : this(
            id = message.id, seq = message.seq, sender = message.sender, text = message.text, kind = message.kind,
            reviewUrl = message.reviewUrl, linkUrl = message.linkUrl, paywallId = message.paywallId,
            paywallTitle = message.paywallTitle, buttonLabel = message.buttonLabel, authorId = message.authorId,
            actionClickedAt = message.actionClickedAt, createdAt = message.createdAt,
            failed = message.status == MessageStatus.FAILED, awaitsManualRetry = message.awaitsManualRetry,
            retryAfterLimitRelease = message.retryAfterLimitRelease,
            retryAfterTransientFailure = message.retryAfterTransientFailure, attachments = message.attachments,
            imageCount = if (message.hasConfirmedAttachments) 0 else message.images.size,
            attachmentIds = if (message.hasConfirmedAttachments) emptyList() else message.attachmentIds,
            expectedImageCount = message.expectedImageCount ?: message.images.size,
        )

        fun message(images: List<ByteArray>): Message = Message(
            id = id, seq = seq, sender = sender, text = text, attachments = attachments, createdAt = createdAt,
            kind = kind, reviewUrl = reviewUrl, linkUrl = linkUrl, paywallId = paywallId, paywallTitle = paywallTitle,
            buttonLabel = buttonLabel, authorId = authorId, actionClickedAt = actionClickedAt,
            status = when {
                seq != null -> MessageStatus.SENT
                failed -> MessageStatus.FAILED
                else -> MessageStatus.SENDING
            },
            awaitsManualRetry = seq == null && awaitsManualRetry,
            retryAfterLimitRelease = seq == null && retryAfterLimitRelease,
            retryAfterTransientFailure = seq == null && retryAfterTransientFailure,
            images = images,
            attachmentIds = if (hasConfirmedAttachments) emptyList() else attachmentIds,
            expectedImageCount = expectedImageCount ?: imageCount,
        )
    }

    private companion object {
        val serializer = ListSerializer(Snapshot.serializer())
    }
}
