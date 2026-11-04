package sh.reception.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.Reception
import sh.reception.Theme
import sh.reception.configuration.ResolvedAppearance
import sh.reception.model.Message

/** Grouping, timestamps, the delivery status line and the outgoing fade count. The appearance reads localized defaults. */
@RunWith(RobolectricTestRunner::class)
class MessageLayoutTest {
    private val appearance = ResolvedAppearance.local(Reception.Appearance())
    private val start = 1_800_000_000_000L - 1_800_000_000_000L % 60_000

    private fun message(id: String, sender: String, at: Long, text: String = "t") =
        Message(id = id, sender = sender, text = text, attachments = emptyList(), createdAt = start + at)

    private fun layouts(messages: List<Message>) = messages.indices.map { MessageLayout.of(messages, it, appearance) }

    @Test
    fun oneAuthorWithinAMinuteGroupsTightlyAndAGapStartsAGroup() {
        val layout = layouts(listOf(
            message("a", "USER", 0), message("b", "USER", 20_000), message("c", "AGENT", 30_000),
            message("d", "AGENT", 70_000),
        ))
        assertEquals(listOf(Theme.zero, Theme.groupedSpacing, Theme.groupSpacing, Theme.groupSpacing), layout.map { it.spacing })
    }

    @Test
    fun aTimestampShowsOnlyAtAGroupMoreThanFiveMinutesAfterThePreviousMessage() {
        val layout = layouts(listOf(
            message("a", "USER", 0), message("b", "AGENT", 60_000), message("c", "AGENT", 60_000 + 5 * 60_000),
            message("d", "USER", 60_000 + 5 * 60_000 + 1 + 5 * 60_000),
        ))
        assertEquals(listOf(true, false, false, true), layout.map { it.showsTimestamp })
    }

    @Test
    fun onlyTheLatestOutgoingMessageShowsItsDeliveryStatus() {
        val layout = layouts(listOf(message("a", "USER", 0), message("b", "USER", 1_000), message("c", "AGENT", 2_000)))
        assertEquals(listOf(false, true, false), layout.map { it.showsDeliveryStatus })
    }

    @Test
    fun olderOutgoingBubblesCountOnlyNewerOutgoingTextMessages() {
        val layout = layouts(listOf(
            message("a", "USER", 0), message("b", "AGENT", 1_000), message("c", "USER", 2_000, text = ""),
            message("d", "USER", 3_000),
        ))
        assertEquals(listOf(1, 1, 1, 0), layout.map { it.olderOutgoingCount })
    }

    @Test
    fun withoutTeamPhotosNoReplyReservesThePhotoColumn() {
        val layout = layouts(listOf(message("a", "AGENT", 0)))
        assertFalse(layout.single().reservesPhotoColumn)
        assertTrue(layout.single().author == null)
    }
}
