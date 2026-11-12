package sh.reception.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import java.text.NumberFormat
import java.util.Locale

/**
 * Decides when the chat follows new content and when it offers the jump-to-latest control. A reader at the newest
 * message keeps following; a reader further up keeps their place and sees how many replies arrived below.
 */
internal class ChatScrollState {
    data class Entry(val id: String, val sender: String)

    /**
     * Whether the newest message row is on screen. It stays set while new rows lay out below it, so the chat keeps
     * following until that row has actually left the screen.
     */
    var showsNewest: Boolean = true
        private set

    /**
     * Team replies that arrived below while the reader was further up, by message ID so a reload never counts twice.
     * Compose state, so the jump control appears and counts as they arrive.
     */
    private var unseenIds: Set<String> by mutableStateOf(emptySet())
    private var knownIds: Set<String> = emptySet()
    private var visibleNewestId: String? = null

    val unseenCount: Int
        get() = unseenIds.size

    /** Layout changes, the typing indicator and replies only scroll a reader who is at the newest message. */
    val followsNewest: Boolean
        get() = showsNewest
    val showsJumpControl: Boolean
        get() = unseenIds.isNotEmpty()

    /**
     * Returns whether to scroll to the newest message after the history changed. The reader's own message always
     * scrolls; team replies only follow a reader at the newest message and are counted otherwise.
     */
    fun messagesChanged(messages: List<Entry>): Boolean {
        val previous = knownIds
        knownIds = messages.map { it.id }.toSet()
        unseenIds = unseenIds intersect knownIds
        // A first load, or a history that shares nothing with the previous one, starts at the end.
        val lastKnown = messages.indexOfLast { it.id in previous }
        if (lastKnown < 0) {
            jumpedToNewest()
            return messages.isNotEmpty()
        }
        // Only messages below everything already shown count as new; a reload that fills in older ones doesn't.
        val arrivals = messages.drop(lastKnown + 1).filter { it.id !in previous }
        if (arrivals.any { it.sender == USER_SENDER }) {
            jumpedToNewest()
            return true
        }
        if (arrivals.isEmpty() || showsNewest) return arrivals.isNotEmpty()
        unseenIds = unseenIds + arrivals.map { it.id }
        return false
    }

    /**
     * The newest row moved on or off screen. A row that was never on screen can't leave it, so a reply laid out below
     * a reader at the newest message doesn't stop the chat from following it there.
     */
    fun newest(id: String, isVisible: Boolean) {
        if (isVisible) {
            visibleNewestId = id
            showsNewest = true
            unseenIds = emptySet()
        } else if (visibleNewestId == id) {
            visibleNewestId = null
            showsNewest = false
        }
    }

    /** The reader tapped the control or sent a message; both end at the newest message. */
    fun jumpedToNewest() {
        showsNewest = true
        unseenIds = emptySet()
    }

    companion object {
        const val USER_SENDER: String = "USER"

        /**
         * The unread label, such as "3 new messages", capped at "99+ new messages" so the control stays compact.
         * [format] returns the localized plural for a count; null without unseen replies.
         */
        fun unreadText(count: Int, locale: Locale = ReceptionLocalization.locale, format: (Int) -> String): String? {
            if (count <= 0) return null
            val limit = Theme.JUMP_TO_LATEST_COUNT_LIMIT
            if (count <= limit) return format(count)
            val text = format(limit)
            val number = NumberFormat.getInstance(locale).format(limit)
            return if (number in text) text.replaceFirst(number, "$number+") else text
        }
    }
}
