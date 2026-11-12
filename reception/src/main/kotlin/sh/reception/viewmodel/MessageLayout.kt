package sh.reception.viewmodel

import android.graphics.Bitmap
import androidx.compose.ui.unit.Dp
import sh.reception.Theme
import sh.reception.configuration.ResolvedAppearance
import sh.reception.model.Message
import sh.reception.model.TeamMember

internal data class MessageLayout(
    val showsDeliveryStatus: Boolean,
    val showsTimestamp: Boolean,
    val olderOutgoingCount: Int,
    val spacing: Dp,
    /** Set for agent replies whose author is known while the app shows team names or photos. */
    val author: TeamMember? = null,
    val authorPhoto: Bitmap? = null,
    val showsAuthorName: Boolean = false,
    val showsAuthorPhoto: Boolean = false,
    /** Reserve space even before team details arrive, keeping cached replies aligned. */
    val reservesPhotoColumn: Boolean = false,
) {
    companion object {
        /**
         * The layout of [messages]`[index]`: runs of one author within one minute group tightly, a run more than five
         * minutes after the previous message shows a timestamp, the latest outgoing message shows its delivery status,
         * and older outgoing bubbles count the newer text messages that fade them.
         */
        fun of(
            messages: List<Message>,
            index: Int,
            appearance: ResolvedAppearance,
            team: Map<String, TeamMember> = emptyMap(),
            photos: Map<String, Bitmap> = emptyMap(),
        ): MessageLayout {
            val message = messages.getOrNull(index)
                ?: return MessageLayout(showsDeliveryStatus = false, showsTimestamp = false, olderOutgoingCount = 0,
                    spacing = Theme.zero)
            val previous = messages.getOrNull(index - 1)
            val next = messages.getOrNull(index + 1)
            val startsGroup = previous?.let { !sameGroup(it, message) } ?: true
            val showsAuthors = appearance.showsTeamNames || appearance.showsTeamPhotos
            val author = if (message.sender == "USER" || !showsAuthors) null else message.authorId?.let { team[it] }
            val reservesPhotoColumn = message.sender == "AGENT" && appearance.showsTeamPhotos
            return MessageLayout(
                showsDeliveryStatus = index == messages.indexOfLast { it.sender == "USER" },
                showsTimestamp = startsGroup &&
                    (previous?.let { message.createdAt - it.createdAt > Theme.TIMESTAMP_INTERVAL_MILLIS } ?: true),
                olderOutgoingCount = messages.drop(index + 1).count { it.sender == "USER" && it.text.isNotEmpty() },
                spacing = when {
                    previous == null -> Theme.zero
                    startsGroup -> Theme.groupSpacing
                    else -> Theme.groupedSpacing
                },
                author = author,
                authorPhoto = if (appearance.showsTeamPhotos) message.authorId?.let { photos[it] } else null,
                showsAuthorName = author != null && appearance.showsTeamNames && startsGroup,
                showsAuthorPhoto = author != null && reservesPhotoColumn && (next?.let { !sameGroup(message, it) } ?: true),
                reservesPhotoColumn = reservesPhotoColumn,
            )
        }

        private const val MINUTE_MILLIS = 60_000L

        /** One author within the same clock minute. */
        private fun sameGroup(first: Message, second: Message): Boolean =
            first.sender == second.sender && first.authorId == second.authorId &&
                Math.floorDiv(first.createdAt, MINUTE_MILLIS) == Math.floorDiv(second.createdAt, MINUTE_MILLIS)
    }
}

/** The layout of each message with this chat's team: fresh details first, then the restored ones. */
internal fun ChatModel.messageLayouts(appearance: ResolvedAppearance): List<MessageLayout> {
    val profiles = teamProfiles
    val known = (profiles?.members?.value ?: emptyMap()) + team
    val photos = profiles?.images?.value ?: emptyMap()
    return messages.indices.map { MessageLayout.of(messages, it, appearance, known, photos) }
}
