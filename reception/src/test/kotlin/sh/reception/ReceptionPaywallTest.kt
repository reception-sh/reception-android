package sh.reception

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The iOS validation: limits, duplicates, the product budget, and what the registration entry carries. */
class ReceptionPaywallTest {
    private fun paywall(id: String, title: String = "Pro", products: List<ReceptionPaywall.Product> = emptyList()) =
        ReceptionPaywall(id, title, products = products)

    @Test
    fun idsAndTitlesAreTrimmedAndBounded() {
        val valid = ReceptionPaywall.validated(listOf(
            paywall("  pro  ", " Pro "),
            paywall("a".repeat(64)),
            paywall("b".repeat(65)),
            paywall("has space"),
            paywall("x", ""),
            paywall("y", "t".repeat(61)),
            paywall("z", "t".repeat(60)),
            paywall("dots.and-dashes_ok"),
        ))
        assertEquals(listOf("pro", "a".repeat(64), "z", "dots.and-dashes_ok"), valid.map { it.id })
        assertEquals("Pro", valid.first().title)
    }

    @Test
    fun theFirstOfARepeatedIdWinsAndAtMostTwentyAreKept() {
        val valid = ReceptionPaywall.validated(listOf(paywall("p", "First"), paywall("p", "Second")) +
            (1..25).map { paywall("p$it") })
        assertEquals("First", valid.first().title)
        assertEquals(20, valid.size)
        assertEquals("p19", valid.last().id)
    }

    @Test
    fun productsAreCappedPerPaywallAndByTheBudget() {
        val products = (1..12).map { ReceptionPaywall.Product("product$it") }
        val valid = ReceptionPaywall.validated((1..5).map { paywall("p$it", products = products) })
        assertEquals(listOf(10, 10, 10, 10, 0), valid.map { it.products.size })
        assertEquals("product10", valid.first().products.last().id)
    }

    @Test
    fun malformedDetailsAreDroppedButThePaywallStays() {
        val products = listOf(
            ReceptionPaywall.Product(" monthly ", price = " $4.99 ", period = "P1M", trial = "P1W"),
            ReceptionPaywall.Product("yearly", price = "p".repeat(41), period = "1 month", trial = "P1234D"),
            ReceptionPaywall.Product(" "),
        )
        val valid = ReceptionPaywall.validated(listOf(
            ReceptionPaywall("pro", "Pro", ReceptionPaywall.Source("Not Valid"), products),
        )).single()
        assertNull(valid.source)
        assertEquals(listOf(ReceptionPaywall.Product("monthly", "$4.99", "P1M", "P1W"), ReceptionPaywall.Product("yearly")),
            valid.products)
    }

    @Test
    fun theEntryCarriesOnlyWhatTheAppSet() {
        assertEquals(JsonObject(mapOf("id" to JsonPrimitive("p"), "title" to JsonPrimitive("Pro"))), paywall("p").payload)
        val full = ReceptionPaywall("p", "Pro", ReceptionPaywall.Source.RevenueCat,
            listOf(ReceptionPaywall.Product("m", price = "$1")), presentable = false).payload
        assertEquals(JsonPrimitive("revenuecat"), full["source"])
        assertEquals(JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("m"), "price" to JsonPrimitive("$1"))))),
            full["products"])
        assertEquals(JsonPrimitive(false), full["presentable"])
    }
}
