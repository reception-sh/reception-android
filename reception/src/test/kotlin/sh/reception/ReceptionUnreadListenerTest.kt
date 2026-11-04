package sh.reception

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `addUnreadCountListener`: the current count at once, then only changes, until removed. */
@RunWith(RobolectricTestRunner::class)
class ReceptionUnreadListenerTest {
    @After
    fun reset() = ReceptionUnread.set(0, syncsNotifications = false)

    @Test
    fun aListenerGetsTheCurrentCountAndEveryChangeUntilRemoved() {
        ReceptionUnread.set(2, syncsNotifications = false)
        val seen = mutableListOf<Int>()
        val listener: (Int) -> Unit = { seen += it }
        Reception.addUnreadCountListener(listener)
        Reception.addUnreadCountListener(listener)
        ReceptionUnread.set(2, syncsNotifications = false)
        ReceptionUnread.set(3, syncsNotifications = false)
        ReceptionUnread.set(-1, syncsNotifications = false)
        Reception.removeUnreadCountListener(listener)
        ReceptionUnread.set(5, syncsNotifications = false)
        assertEquals(listOf(2, 3, 0), seen)
    }
}
