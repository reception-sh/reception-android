package sh.reception

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Availability checks: only the latest one publishes; invalidation keeps info-only entries; a failing check hides. */
@RunWith(RobolectricTestRunner::class)
class ReceptionPaywallsTest {
    private val pro = ReceptionPaywall("pro", "Pro")
    private val sale = ReceptionPaywall("sale", "Sale")
    private val info = ReceptionPaywall("info", "Info", presentable = false)

    @After
    fun tearDown() {
        ReceptionPaywalls.availability = null
        ReceptionPaywalls.register(emptyList())
        ReceptionPaywalls.onPaywall = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun withoutAvailabilityEveryPaywallIsAvailable() {
        ReceptionPaywalls.register(listOf(pro, info))
        assertEquals(listOf(pro, info), ReceptionPaywalls.available)
    }

    @Test
    fun onlyTheLatestCheckPublishes() {
        val slow = CompletableDeferred<Boolean>()
        var calls = 0
        ReceptionPaywalls.register(listOf(pro, sale, info))
        ReceptionPaywalls.availability = { id ->
            calls += 1
            if (calls == 1) slow.await() else id == "sale"
        }
        idle()
        assertEquals(listOf(info), ReceptionPaywalls.available)
        ReceptionPaywalls.refresh()
        idle()
        assertEquals(listOf(sale, info), ReceptionPaywalls.available)
        slow.complete(true)
        idle()
        assertEquals(listOf(sale, info), ReceptionPaywalls.available)
    }

    @Test
    fun aFailingCheckLeavesThePaywallUnavailable() {
        ReceptionPaywalls.register(listOf(pro, sale))
        ReceptionPaywalls.availability = { id -> if (id == "pro") error("vendor down") else true }
        idle()
        assertEquals(listOf(sale), ReceptionPaywalls.available)
    }

    @Test
    fun aCardOpensOnlyAnAvailablePresentablePaywallWithAHandler() {
        ReceptionPaywalls.register(listOf(pro, info))
        assertTrue(!ReceptionPaywalls.canOpen("pro"))
        ReceptionPaywalls.onPaywall = {}
        assertTrue(ReceptionPaywalls.canOpen("pro"))
        assertTrue(!ReceptionPaywalls.canOpen("info"))
        assertTrue(!ReceptionPaywalls.canOpen("missing"))
    }
}
