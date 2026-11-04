package sh.reception.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {
    @Test
    fun theSchemeAllowlistMatchesTheService() {
        for (url in listOf("https://a.b", "HTTP://a.b", "mailto:a@b.c", "tel:+1", "sms:+1", "myapp://x", "fb123+x.y-z:open")) {
            assertTrue(url, LinkPolicy.isAllowed(url))
        }
        for (url in listOf("javascript:alert(1)", "JavaScript:x", "data:text/html,x", "file:///etc", "blob:x", "about:blank",
            "vbscript:x", "no-scheme", "1abc://x", "", ":x")) {
            assertFalse(url, LinkPolicy.isAllowed(url))
        }
    }

    @Test
    fun webLinksExcludeGooglePlay() {
        assertTrue(LinkPolicy.isWebLink("https://wa.me/123"))
        assertTrue(LinkPolicy.isWebLink("http://example.com"))
        assertFalse(LinkPolicy.isWebLink("https://play.google.com/store/apps/details?id=x"))
        assertFalse(LinkPolicy.isWebLink("https://PLAY.GOOGLE.COM/store"))
        assertFalse(LinkPolicy.isWebLink("mailto:a@b.c"))
        assertFalse(LinkPolicy.isWebLink("market://details?id=x"))
        assertFalse(LinkPolicy.isWebLink("https://"))
    }

    @Test
    fun hostLinksAreTheHostsOwnHandlersCachedPerSchemeAndHost() {
        val queried = mutableListOf<String>()
        val policy = LinkPolicy("com.host") { url ->
            queried.add(url)
            if (url.startsWith("myapp:") || url.startsWith("https://host.example")) listOf("com.android.chrome", "com.host") else listOf("com.android.chrome")
        }
        assertTrue(policy.isHostScheme("myapp://settings"))
        assertTrue(policy.isHostScheme("MYAPP://settings/privacy"))
        assertTrue(policy.isHostScheme("https://host.example/a"))
        assertFalse(policy.isHostScheme("https://other.example/a"))
        assertFalse(policy.isHostScheme("https://other.example/b"))
        assertFalse(policy.isHostScheme("not a url"))
        assertEquals(listOf("myapp://settings", "https://host.example/a", "https://other.example/a"), queried)
    }
}
