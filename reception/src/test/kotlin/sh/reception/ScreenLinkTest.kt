package sh.reception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `reception-screen:` links and the screens the device reports, as iOS `ScreenLink`. */
class ScreenLinkTest {
    @Test
    fun aScreenLinkNamesItsScreenAndOptionalValue() {
        assertEquals(ScreenLink("settings", null), ScreenLink.parse("reception-screen:settings"))
        assertEquals(ScreenLink("order", "A 12+3"), ScreenLink.parse("Reception-Screen:order?value=A%2012+3"))
        assertEquals(ScreenLink("order", null), ScreenLink.parse("reception-screen:order?value="))
        assertEquals(ScreenLink("order", "7"), ScreenLink.parse("reception-screen:order?other=1&value=7#top"))
    }

    @Test
    fun otherSchemesAndInvalidIdsAreNotScreenLinks() {
        assertNull(ScreenLink.parse("https://example.com/settings"))
        assertNull(ScreenLink.parse("reception-screen://settings"))
        assertNull(ScreenLink.parse("reception-screen:_settings"))
        assertNull(ScreenLink.parse("reception-screen:" + "a".repeat(65)))
        assertNull(ScreenLink.parse("reception-screen:order?value=%zz"))
        assertNull(ScreenLink.parse("settings"))
    }

    @Test
    fun theReportListsUsableScreensOnceWithShortenedValueNames() {
        val screens = listOf(
            ReceptionScreen("settings") {},
            ReceptionScreen("settings", value = "Ignored") {},
            ReceptionScreen("-bad") {},
            ReceptionScreen("order", value = "N".repeat(45)) {},
        ) + (1..60).map { ReceptionScreen("s$it") {} }
        val report = ScreenLink.report(screens)
        assertEquals(ScreenLink.LIMIT, report.size)
        assertEquals(mapOf("id" to "settings"), report[0])
        assertEquals(mapOf("id" to "order", "value" to "N".repeat(40)), report[1])
    }
}
