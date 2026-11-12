package sh.reception.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import sh.reception.client.ReceptionAPI
import sh.reception.client.RetryScope

@Serializable
internal data class Conversation(
    val id: String,
    val status: String,
    val unreadForUser: Int,
    @Serializable(with = Timestamp::class) val createdAt: Long,
)

/** A team member's first name and a short-lived photo URL, sent with replies while the app shows team photos. */
@Serializable
internal data class TeamMember(
    val id: String,
    val name: String,
    val photoUrl: String? = null,
    val photoId: String? = null,
)

@Serializable
internal data class ConversationResponse(
    val limits: Limits? = null,
    val boundedRead: Boolean? = null,
    val device: Device? = null,
    val conversation: Conversation? = null,
    val messages: List<Message>,
    val team: List<TeamMember>? = null,
    /** Push eligibility of this device; older services omit it. */
    val push: Boolean? = null,
) {
    @Serializable
    data class Device(val blocked: Boolean? = null, val verificationRequired: Boolean? = null)

    /** Remaining waits from `?limits=1`. A malformed value leaves that wait as it is. */
    @Serializable
    data class Limits(private val messages: JsonElement? = null, private val uploads: JsonElement? = null) {
        fun seconds(scope: RetryScope): Int? {
            val value = when (scope) {
                RetryScope.MESSAGES -> messages
                RetryScope.UPLOADS -> uploads
                else -> null
            }
            val primitive = value as? JsonPrimitive ?: return null
            val seconds = primitive.takeIf { !it.isString }?.intOrNull ?: return null
            return seconds.takeIf { it in 0..ReceptionAPI.MAXIMUM_RETRY_AFTER }
        }
    }
}

@Serializable
internal data class ConversationState(val id: String? = null, val status: String)

@Serializable
internal data class MessagesResponse(
    val device: ConversationResponse.Device? = null,
    val boundedRead: Boolean? = null,
    val conversation: ConversationState? = null,
    val messages: List<Message>,
    val team: List<TeamMember>? = null,
    val hasMore: Boolean? = null,
)

@Serializable
internal data class SendResponse(val conversation: Conversation, val message: Message)

@Serializable
internal class EmptyResponse
