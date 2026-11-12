package sh.reception.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import sh.reception.client.RetryScope

/** A chat message: decoded from the service, or created locally until the service confirms it. */
@Serializable
internal data class Message(
    val id: String,
    val clientId: String? = null,
    val seq: Long? = null,
    val sender: String,
    val text: String,
    val attachments: List<Attachment>,
    @Serializable(with = Timestamp::class) val createdAt: Long,
    val kind: String = "TEXT",
    val reviewUrl: String? = null,
    val linkUrl: String? = null,
    val paywallId: String? = null,
    val paywallTitle: String? = null,
    /** Agent-chosen button text; null shows the localized default. */
    val buttonLabel: String? = null,
    /** Team member who wrote an agent reply; set only while the app shows team photos. */
    val authorId: String? = null,
    @Serializable(with = Timestamp::class) val actionClickedAt: Long? = null,
    @Transient val status: MessageStatus = MessageStatus.SENT,
    @Transient val showsPending: Boolean = false,
    /** Timer expiry alone cannot resend this message. */
    @Transient val awaitsManualRetry: Boolean = false,
    /** A verified early release may resume this submitted message. Explicit Cancel removes that permission. */
    @Transient val retryAfterLimitRelease: Boolean = false,
    /** A submitted transport/server failure may resume after reopening, unless explicitly canceled. */
    @Transient val retryAfterTransientFailure: Boolean = false,
    /** The wait that held this message's last attempt; in memory only, so it never outlives the process. */
    @Transient val heldBy: RetryScope? = null,
    /** Prepared JPEG data of photos the service has not confirmed yet. */
    @Transient val images: List<ByteArray> = emptyList(),
    @Transient val attachmentIds: List<String> = emptyList(),
    /** The photo count, kept even when a cached photo cannot be read. */
    @Transient val expectedImageCount: Int? = null,
) {
    val hasConfirmedAttachments: Boolean
        get() = seq != null && attachments.isNotEmpty()
}
