package sh.reception.client

import android.os.Looper
import org.junit.After
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import sh.reception.FacadeState
import sh.reception.client.SubscriptionState.ACTIVE
import sh.reception.client.SubscriptionState.CANCELLED
import sh.reception.client.SubscriptionState.EXPIRED
import sh.reception.testing.MainLooperDrain

/** Play purchases as paid access, and the subscription changes reported as store events. */
@RunWith(RobolectricTestRunner::class)
class PaidAccessTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val context = RuntimeEnvironment.getApplication()

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
        access.publish(emptyList())
        assertEquals(false, FacadeState.subscribed)
        access.publish(listOf(purchase("pro", purchased = false)))
        assertEquals(false, FacadeState.subscribed)
        access.publish(listOf(purchase("lifetime", subscription = false)))
        assertEquals(true, FacadeState.subscribed)
        access.publish(listOf(purchase("pro", renewing = false)))
        assertEquals(true, FacadeState.subscribed)
        assertEquals(2, changes)
    }

    @Test
    fun aFailedReadAfterASuccessfulOneMakesPaidAccessUnknownAndOmitsIt() {
        var answer: List<PlayBillingBridge.Purchase>? = listOf(purchase("pro"))
        var changes = 0
        val access = PaidAccess { answer }.apply { onChange = { changes += 1 } }
        access.refresh(context)
        idle()
        assertEquals(true, access.current)
        assertEquals("true", payload()["subscribed"].toString())
        answer = null
        access.refresh(context)
        idle()
        assertNull(access.current)
        assertFalse(payload().containsKey("subscribed"))
        assertEquals(1, changes)
    }

    @Test
    fun onlyTheLatestReadPublishesAndAWaitingCallerGetsItsAnswer() {
        val first = CompletableDeferred<List<PlayBillingBridge.Purchase>?>()
        val second = CompletableDeferred<List<PlayBillingBridge.Purchase>?>()
        val answers = ArrayDeque(listOf(first, second))
        // Billing ignores cancellation, so the superseded read finishes last and must not publish.
        val access = PaidAccess { withContext(NonCancellable) { answers.removeFirst().await() } }
        var waited: List<PlayBillingBridge.Purchase>? = null
        CoroutineScope(Dispatchers.Main).launch { waited = access.refreshNow(context) }
        idle()
        access.refresh(context)
        idle()
        second.complete(listOf(purchase("pro")))
        idle()
        first.complete(emptyList())
        idle()
        assertEquals(true, access.current)
        assertEquals(listOf("pro"), waited?.flatMap { it.products })
    }

    @Test
    fun aCallerWaitingOnTheMainThreadFollowsAReadThatCancelsItsOwn() {
        val first = CompletableDeferred<List<PlayBillingBridge.Purchase>?>()
        val second = CompletableDeferred<List<PlayBillingBridge.Purchase>?>()
        val answers = ArrayDeque(listOf(first, second))
        // Billing honours cancellation here, and the waiting automation resumes immediately inside the cancel.
        val access = PaidAccess { answers.removeFirst().await() }
        var waited: List<PlayBillingBridge.Purchase>? = null
        var failed: Throwable? = null
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                waited = access.refreshNow(context)
            } catch (error: CancellationException) {
                failed = error
            }
        }
        idle()
        access.refresh(context)
        idle()
        second.complete(listOf(purchase("pro")))
        idle()
        assertNull(failed)
        assertEquals(listOf("pro"), waited?.flatMap { it.products })
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

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun payload() = Json.parseToJsonElement(DeviceInfo.payload(context, DeviceIdentity())).jsonObject
}
