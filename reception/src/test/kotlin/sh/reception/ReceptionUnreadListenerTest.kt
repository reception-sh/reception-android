package sh.reception

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.viewmodel.ChatHarness

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

    @Test
    fun aListenerRemovedByAnEarlierOneDuringADeliveryIsNotCalled() {
        val seen = mutableListOf<String>()
        lateinit var second: (Int) -> Unit
        val first: (Int) -> Unit = { count ->
            seen += "first $count"
            if (count == 1) Reception.removeUnreadCountListener(second)
        }
        second = { count -> seen += "second $count" }
        Reception.addUnreadCountListener(first)
        Reception.addUnreadCountListener(second)
        ReceptionUnread.set(1, syncsNotifications = false)
        Reception.removeUnreadCountListener(first)
        assertEquals(listOf("first 0", "second 0", "first 1"), seen)
    }

    @Test
    fun aLogoutByAListenerDuringADeliveryIsTheLastCountEveryoneSees() {
        val harness = ChatHarness()
        harness.start()
        try {
            val seen = mutableListOf<String>()
            val first: (Int) -> Unit = { count ->
                seen += "first $count"
                if (count == 1) Reception.logout()
            }
            val second: (Int) -> Unit = { count -> seen += "second $count" }
            Reception.addUnreadCountListener(first)
            Reception.addUnreadCountListener(second)
            ReceptionUnread.set(1, syncsNotifications = false)
            Reception.removeUnreadCountListener(first)
            Reception.removeUnreadCountListener(second)
            assertEquals(listOf("first 0", "second 0", "first 1", "first 0", "second 0"), seen)
            assertEquals(0, Reception.unreadCount.value)
        } finally {
            harness.stop()
        }
    }
}
