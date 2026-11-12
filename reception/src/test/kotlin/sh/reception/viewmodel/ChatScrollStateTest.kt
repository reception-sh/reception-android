package sh.reception.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** When new messages scroll the chat and when they count as unseen. */
class ChatScrollStateTest {
    private fun entries(vararg ids: String) = ids.map { ChatScrollState.Entry(it, if (it.startsWith("u")) "USER" else "AGENT") }

    @Test
    fun aFirstLoadScrollsToTheEnd() {
        val state = ChatScrollState()
        assertTrue(state.messagesChanged(entries("a1", "a2")))
        assertEquals(0, state.unseenCount)
    }

    @Test
    fun repliesFollowAReaderAtTheNewestMessage() {
        val state = ChatScrollState()
        state.messagesChanged(entries("a1"))
        state.newest("a1", isVisible = true)
        assertTrue(state.messagesChanged(entries("a1", "a2")))
        assertEquals(0, state.unseenCount)
    }

    @Test
    fun repliesBelowAReaderFurtherUpCountOnceAndTheReadersOwnMessageScrolls() {
        val state = ChatScrollState()
        state.messagesChanged(entries("a1"))
        state.newest("a1", isVisible = true)
        state.newest("a1", isVisible = false)
        assertFalse(state.messagesChanged(entries("a1", "a2", "a3")))
        // A reload of the same history does not count the replies again.
        assertFalse(state.messagesChanged(entries("a1", "a2", "a3")))
        assertEquals(2, state.unseenCount)
        assertTrue(state.showsJumpControl)
        assertTrue(state.messagesChanged(entries("a1", "a2", "a3", "u1")))
        assertEquals(0, state.unseenCount)
    }

    @Test
    fun olderMessagesFilledInAboveDoNotCount() {
        val state = ChatScrollState()
        state.messagesChanged(entries("a2"))
        state.newest("a2", isVisible = true)
        state.newest("a2", isVisible = false)
        assertFalse(state.messagesChanged(entries("a1", "a2")))
        assertEquals(0, state.unseenCount)
    }

    @Test
    fun aRowThatWasNeverOnScreenCannotLeaveIt() {
        val state = ChatScrollState()
        state.messagesChanged(entries("a1"))
        state.newest("a2", isVisible = false)
        assertTrue(state.followsNewest)
    }

    @Test
    fun theUnreadLabelIsCappedAtNinetyNine() {
        assertEquals(null, ChatScrollState.unreadText(0, Locale.ENGLISH) { "$it new messages" })
        assertEquals("3 new messages", ChatScrollState.unreadText(3, Locale.ENGLISH) { "$it new messages" })
        assertEquals("99+ new messages", ChatScrollState.unreadText(140, Locale.ENGLISH) { "$it new messages" })
    }
}
