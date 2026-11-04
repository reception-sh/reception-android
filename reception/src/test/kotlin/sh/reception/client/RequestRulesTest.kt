package sh.reception.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** App ID validation, retry scope routing and gating, and the server cooldown rules of SDK_SPEC.md. */
class RequestRulesTest {
    @Test
    fun acceptsOnlyWellFormedAppIds() {
        assertTrue(ReceptionAPI.accepts("app_lQCXNRFINXc8FEnriGpad3"))
        assertFalse(ReceptionAPI.accepts("app_lQCXNRFINXc8FEnriGpad"))
        assertFalse(ReceptionAPI.accepts("app_lQCXNRFINXc8FEnriGpad3x"))
        assertFalse(ReceptionAPI.accepts("ids_lQCXNRFINXc8FEnriGpad3"))
        assertFalse(ReceptionAPI.accepts("app_lQCXNRFINXc8FEnriGpa-3"))
        assertFalse(ReceptionAPI.accepts(" app_lQCXNRFINXc8FEnriGpad3"))
        assertFalse(ReceptionAPI.accepts("app_lQCXNRFINXc8FEnriGpad3\n"))
    }

    @Test
    fun routesEachLimitToItsEndpoint() {
        assertEquals(RetryScope.MESSAGES, RetryScope.route("conversation/messages", "POST"))
        assertNull(RetryScope.route("conversation/messages?after=4", "GET"))
        assertEquals(RetryScope.UPLOADS, RetryScope.route("uploads", "POST"))
        assertEquals(RetryScope.STREAM, RetryScope.route("conversation/events", "GET"))
        assertEquals(RetryScope.SESSION, RetryScope.route("devices", "POST"))
        assertEquals(RetryScope.SESSION, RetryScope.route("devices/me/token", "POST"))
        assertNull(RetryScope.route("devices/me", "PATCH"))
    }

    @Test
    fun gatesRequestsOnTheirRouteUploadsAndRequestBudget() {
        assertEquals(listOf(RetryScope.MESSAGES, RetryScope.UPLOADS, RetryScope.REQUESTS),
            RetryScope.gates("conversation/messages", "POST", authenticated = true, photos = true))
        assertEquals(listOf(RetryScope.REQUESTS), RetryScope.gates("conversation/unread", "GET", authenticated = true))
        assertEquals(listOf(RetryScope.SESSION), RetryScope.gates("devices", "POST", authenticated = false))
        assertEquals(emptyList<RetryScope>(), RetryScope.gates("devices/me", "DELETE", authenticated = false))
        assertEquals(emptyList<RetryScope>(), RetryScope.gates("devices/me/logout", "POST", authenticated = false))
    }

    private fun wait(status: Int, header: String?, scope: String? = null, path: String = "conversation/unread",
                     method: String = "GET", authenticated: Boolean = true) =
        ReceptionAPI.wait(status, header, scope, path, method, authenticated)

    @Test
    fun parsesRetryAfterWithTheOneDayCap() {
        assertEquals(ServerWait(30, RetryScope.REQUESTS), wait(429, "30"))
        assertEquals(ServerWait(30, RetryScope.REQUESTS), wait(429, " 30 "))
        assertEquals(ServerWait(86_400, RetryScope.REQUESTS), wait(429, "100000"))
        assertEquals(ServerWait(86_400, RetryScope.REQUESTS), wait(429, "99999999999"))
        assertEquals(ServerWait(1, RetryScope.REQUESTS), wait(429, "0"))
        assertEquals(ServerWait(null, null), wait(429, "-5"))
        assertEquals(ServerWait(null, null), wait(429, "Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(ServerWait(null, null), wait(429, null))
    }

    @Test
    fun namesTheScopeOfA429() {
        assertEquals(ServerWait(5, RetryScope.UPLOADS), wait(429, "5", scope = "uploads"))
        // An unknown scope falls back to the route, then the request budget of bearer requests.
        assertEquals(ServerWait(5, RetryScope.MESSAGES), wait(429, "5", "future", "conversation/messages", "POST"))
        assertEquals(ServerWait(5, RetryScope.SESSION), wait(429, "5", null, "devices", "POST", authenticated = false))
        assertEquals(ServerWait(5, null), wait(429, "5", null, "appearance", "GET", authenticated = false))
        assertEquals(ServerWait(5, null), wait(429, "5", "requests", "devices/me", "DELETE", authenticated = false))
    }

    @Test
    fun waitsOnA503OnlyWithAScopeAndRetryAfter() {
        assertEquals(ServerWait(2, RetryScope.UPLOADS), wait(503, "2", scope = "uploads", path = "uploads", method = "POST"))
        assertEquals(ServerWait(1, RetryScope.UPLOADS), wait(503, "0", scope = "uploads"))
        assertEquals(ServerWait(null, null), wait(503, "2"))
        assertEquals(ServerWait(null, null), wait(503, null, scope = "uploads"))
        assertEquals(ServerWait(null, null), wait(503, "2", scope = "unknown"))
        assertEquals(ServerWait(null, null), wait(500, "2", scope = "uploads"))
    }

    @Test
    fun aMalformedScopeNeverHidesTheErrorCode() {
        val json = ReceptionAPI.json
        val envelope = ErrorEnvelope.parse(json,
            """{"error":{"code":"chat_reset","message":"m","resetRevision":3,"retryScope":42}}""")
        assertEquals(ErrorEnvelope("chat_reset", "m", 3, null), envelope)
        assertEquals(ErrorEnvelope("rate_limited", "m", null, "uploads"),
            ErrorEnvelope.parse(json, """{"error":{"code":"rate_limited","message":"m","retryScope":"uploads"}}"""))
        assertNull(ErrorEnvelope.parse(json, "<html>"))
        assertNull(ErrorEnvelope.parse(json, """{"error":{"message":"m"}}"""))
    }

    @Test
    fun classifiesTransientFailures() {
        assertTrue(ReceptionAPIError.isTransient(ReceptionAPIError("connection_failed", 0, networkError = "SocketTimeoutException")))
        assertTrue(ReceptionAPIError.isTransient(ReceptionAPIError("internal_error", 500)))
        assertTrue(ReceptionAPIError.isTransient(ReceptionAPIError("timeout", 408)))
        assertTrue(ReceptionAPIError.isTransient(ReceptionAPIError("rate_limited", 429)))
        assertFalse(ReceptionAPIError.isTransient(ReceptionAPIError("blocked", 403)))
        assertFalse(ReceptionAPIError.isTransient(ReceptionAPIError("cancelled", 0)))
    }
}
