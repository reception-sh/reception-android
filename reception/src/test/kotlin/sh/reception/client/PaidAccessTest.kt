package sh.reception.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.reception.FacadeState
import sh.reception.client.SubscriptionState.ACTIVE
import sh.reception.client.SubscriptionState.CANCELLED
import sh.reception.client.SubscriptionState.EXPIRED

/** Play purchases as paid access, and the subscription changes reported as store events. */
class PaidAccessTest {
    private fun purchase(product: String, subscription: Boolean = true, purchased: Boolean = true, renewing: Boolean = true) =
        PlayBillingBridge.Purchase(listOf(product), subscription, purchased, renewing)

    @After
    fun unknown() {
        FacadeState.subscribed = null
    }

    @Test
    fun anyPurchasedItemIsPaidAccessAndOnlyAChangeIsReported() {
        var changes = 0
        val access = PaidAccess { null }.apply { onChange = { changes += 1 } }
        assertNull(access.current)
        access.apply(emptyList())
        assertEquals(false, FacadeState.subscribed)
        access.apply(listOf(purchase("pro", purchased = false)))
        assertEquals(false, FacadeState.subscribed)
        access.apply(listOf(purchase("lifetime", subscription = false)))
        assertEquals(true, FacadeState.subscribed)
        access.apply(listOf(purchase("pro", renewing = false)))
        assertEquals(true, FacadeState.subscribed)
        assertEquals(2, changes)
    }

    @Test
    fun aSubscriptionIsActiveCancelledOrGoneAsExpired() {
        val states = StoreSubscriptions.states(
            known = mapOf("old" to "active", "older" to "expired", "pro" to "active"),
            purchases = listOf(purchase("pro", renewing = false), purchase("plus"), purchase("lifetime", subscription = false),
                purchase("pending", purchased = false)),
        )
        assertEquals(mapOf("pro" to CANCELLED, "plus" to ACTIVE, "old" to EXPIRED), states)
    }

    @Test
    fun onlyChangesBecomeEventsAndFirstSightIsSilent() {
        assertNull(StoreSubscriptions.event(null, ACTIVE))
        assertNull(StoreSubscriptions.event(null, CANCELLED))
        assertNull(StoreSubscriptions.event(null, EXPIRED))
        assertNull(StoreSubscriptions.event(ACTIVE, ACTIVE))
        assertEquals("subscription_cancelled", StoreSubscriptions.event(ACTIVE, CANCELLED))
        assertEquals("subscription_expired", StoreSubscriptions.event(ACTIVE, EXPIRED))
        assertEquals("subscription_expired", StoreSubscriptions.event(CANCELLED, EXPIRED))
        assertNull(StoreSubscriptions.event(CANCELLED, ACTIVE))
        assertNull(StoreSubscriptions.event(EXPIRED, ACTIVE))
        assertNull(StoreSubscriptions.event(EXPIRED, CANCELLED))
    }
}
