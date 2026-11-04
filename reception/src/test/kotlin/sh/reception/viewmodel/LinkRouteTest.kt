package sh.reception.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC.md link paths: screens, then the host's own links, then web pages in the app, then the system. */
class LinkRouteTest {
    private val host = setOf("myapp://orders/7", "https://myapp.example/orders/7")

    private fun route(url: String, inApp: Boolean = true) = LinkRoute.of(url, { it in host }, inApp)

    @Test
    fun screenLinksComeFirst() {
        assertEquals(LinkRoute.SCREEN, route("reception-screen:settings"))
        assertEquals(LinkRoute.SCREEN, route("reception-screen:order?value=7", inApp = false))
    }

    @Test
    fun linksTheHostHandlesCloseTheChat() {
        assertEquals(LinkRoute.HOST, route("myapp://orders/7"))
        assertEquals(LinkRoute.HOST, route("https://myapp.example/orders/7"))
    }

    @Test
    fun webPagesOpenInTheAppUnlessTheSettingIsOff() {
        assertEquals(LinkRoute.WEB, route("https://example.com/help"))
        assertEquals(LinkRoute.WEB, route("http://example.com"))
        assertEquals(LinkRoute.SYSTEM, route("https://example.com/help", inApp = false))
    }

    @Test
    fun playStoreAndOtherSchemesGoToTheSystem() {
        assertEquals(LinkRoute.SYSTEM, route("https://play.google.com/store/apps/details?id=sh.reception"))
        assertEquals(LinkRoute.SYSTEM, route("mailto:help@example.com"))
        assertEquals(LinkRoute.SYSTEM, route("tel:+15551234"))
        assertEquals(LinkRoute.SYSTEM, route("otherapp://x"))
    }
}
