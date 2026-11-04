package sh.reception.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.reception.model.MessageStatus

/** The setup check's log reasons are the iOS ones. */
class SetupCheckTest {
    @Test
    fun aSentMessagePassesAndAFailedOneWasNotDelivered() {
        assertEquals(SetupCheck.Outcome.PASSED, SetupCheck.outcome(MessageStatus.SENT))
        assertEquals(SetupCheck.Outcome.NOT_DELIVERED, SetupCheck.outcome(MessageStatus.FAILED))
        assertNull(SetupCheck.outcome(MessageStatus.SENDING))
        assertNull(SetupCheck.outcome(null))
    }

    @Test
    fun failureReasonsMatchIos() {
        assertEquals(
            listOf(null, "app_id_mismatch", "send_not_allowed", "not_delivered", "timeout", "app_not_active"),
            SetupCheck.Outcome.entries.map { it.reason },
        )
    }
}
