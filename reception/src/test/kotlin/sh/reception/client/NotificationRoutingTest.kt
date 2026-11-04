package sh.reception.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.reception.client.NotificationRouting.Action

/** Receipts and taps act once per key, a tap before configure waits for the drain; port of the Flutter plugin's cases. */
class NotificationRoutingTest {
    @Test
    fun aTapOpensOnceAndItsRepeatIsIgnored() {
        val routing = NotificationRouting()
        assertEquals(Action.RECEIVE_AND_OPEN, routing.action("tap", openChat = true))
        assertEquals(Action.IGNORE, routing.action("tap", openChat = true))
        assertEquals(Action.IGNORE, routing.action("tap", openChat = false))
    }

    @Test
    fun aReceiptThenItsTapOnlyOpens() {
        val routing = NotificationRouting()
        assertEquals(Action.RECEIVE, routing.action("reply", openChat = false))
        assertEquals(Action.IGNORE, routing.action("reply", openChat = false))
        assertEquals(Action.OPEN, routing.action("reply", openChat = true))
        assertEquals(Action.IGNORE, routing.action("reply", openChat = true))
    }

    @Test
    fun aPendingTapDrainsOnceAndAbsorbsTapsMeanwhile() {
        val routing = NotificationRouting()
        routing.queueTap("first", "conversation-1")
        routing.queueTap("latest", "conversation-2")
        assertTrue(routing.hasPendingTap)
        // Receipts still route; taps wait for the drain, which opens the chat once.
        assertEquals(Action.RECEIVE, routing.action("receipt", openChat = false))
        assertEquals(Action.IGNORE, routing.action("other", openChat = true))
        assertEquals("conversation-2", routing.drainPendingTap())
        assertFalse(routing.hasPendingTap)
        assertNull(routing.drainPendingTap())
        assertEquals(Action.IGNORE, routing.action("latest", openChat = true))
        assertEquals(Action.IGNORE, routing.action("other", openChat = true))
        assertEquals(Action.RECEIVE_AND_OPEN, routing.action("next", openChat = true))
    }

    @Test
    fun theOldestKeysAreForgottenBeyondTheBound() {
        val routing = NotificationRouting()
        (0 until NotificationRouting.CAPACITY).forEach { routing.action("key-$it", openChat = true) }
        assertEquals(Action.IGNORE, routing.action("key-0", openChat = true))
        routing.action("key-${NotificationRouting.CAPACITY}", openChat = true)
        // key-0 was the oldest, so the 65th key evicted it; key-1 is still known.
        assertEquals(Action.IGNORE, routing.action("key-1", openChat = true))
        assertEquals(Action.RECEIVE_AND_OPEN, routing.action("key-0", openChat = true))
        assertEquals(Action.IGNORE, routing.action("key-${NotificationRouting.CAPACITY}", openChat = true))
    }
}
