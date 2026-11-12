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
    fun answersAreCachedPerCompleteUrl() {
        val queried = mutableListOf<String>()
        val policy = LinkPolicy("com.host") { url ->
            queried.add(url)
            if (url.startsWith("https://host.example/app/")) listOf("com.android.chrome", "com.host") else listOf("com.android.chrome")
        }
        assertTrue(policy.isHostScheme("https://host.example/app/a"))
        assertTrue(policy.isHostScheme("https://host.example/app/a"))
        assertFalse(policy.isHostScheme("https://host.example/web/a"))
        assertFalse(policy.isHostScheme("https://HOST.example/app/a"))
        assertFalse(policy.isHostScheme("not a url"))
        assertEquals(listOf("https://host.example/app/a", "https://host.example/web/a", "https://HOST.example/app/a"), queried)
    }

    @Test
    fun theCacheKeepsTheMostRecentEntries() {
        var queries = 0
        val policy = LinkPolicy("com.host") { queries++; emptyList() }
        for (index in 0..64) policy.isHostScheme("myapp://item/$index")
        policy.isHostScheme("myapp://item/64")
        assertEquals(65, queries)
        policy.isHostScheme("myapp://item/0")
        assertEquals(66, queries)
    }

    @Test
    fun mixedCaseSchemesAreQueriedInLowercase() {
        val queried = mutableListOf<String>()
        val policy = LinkPolicy("com.host") { url -> queried.add(url); if (url.startsWith("myapp:")) listOf("com.host") else emptyList() }
        assertTrue(policy.isHostScheme("MyApp://Orders/7"))
        assertTrue(policy.isHostScheme("myapp://Orders/7"))
        assertEquals(listOf("myapp://Orders/7"), queried)
        assertEquals("https://Example.com/A", LinkPolicy.normalizedScheme("HTTPS://Example.com/A"))
        assertEquals("not a url", LinkPolicy.normalizedScheme("not a url"))
        assertFalse(LinkPolicy.isAllowed("JAVASCRIPT:alert(1)"))
    }
}
