package sh.reception.client

import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTokenTest {
    private fun token(payload: String): String =
        "eyJhbGciOiJIUzI1NiJ9.${payload.encodeUtf8().base64Url().trimEnd('=')}.signature"

    @Test
    fun readsAStringUserId() {
        assertEquals("user-123", IdentityToken.userId(token("""{"user_id":"user-123","exp":1}""")))
        assertEquals("Zoë ✓", IdentityToken.userId(token("""{"user_id":"Zoë ✓"}""")))
    }

    @Test
    fun rendersAnIntegerUserIdAsDecimal() {
        assertEquals("42", IdentityToken.userId(token("""{"user_id":42}""")))
        assertEquals("-7", IdentityToken.userId(token("""{"user_id":-7}""")))
        assertEquals("1000", IdentityToken.userId(token("""{"user_id":1e3}""")))
        assertEquals("9007199254740991", IdentityToken.userId(token("""{"user_id":9007199254740991}""")))
    }

    @Test
    fun rejectsUserIdsTheServiceWouldNotAccept() {
        assertNull(IdentityToken.userId(token("""{"user_id":9007199254740993}""")))
        assertNull(IdentityToken.userId(token("""{"user_id":1.5}""")))
        assertNull(IdentityToken.userId(token("""{"user_id":true}""")))
        assertNull(IdentityToken.userId(token("""{"user_id":null}""")))
        assertNull(IdentityToken.userId(token("""{"user_id":""}""")))
        assertNull(IdentityToken.userId(token("""{"user_id":"${"a".repeat(256)}"}""")))
        assertNull(IdentityToken.userId(token("""{"sub":"user-123"}""")))
        assertEquals("a".repeat(255), IdentityToken.userId(token("""{"user_id":"${"a".repeat(255)}"}""")))
    }

    @Test
    fun rejectsMalformedTokens() {
        assertNull(IdentityToken.userId("not a token"))
        assertNull(IdentityToken.userId("a.b"))
        assertNull(IdentityToken.userId("a.b.c.d"))
        assertNull(IdentityToken.userId("header.%%%.signature"))
        assertNull(IdentityToken.userId(token("[1,2]")))
        assertNull(IdentityToken.userId(token("{not json")))
        assertNull(IdentityToken.userId(token("""{"user_id":"x","pad":"${"p".repeat(4096)}"}""")))
    }

    @Test
    fun matchesTheVerifiedUserFirst() {
        val token = IdentityToken("t", "user-1")
        assertTrue(token.matchesUser(verifiedUserId = "user-1", unverifiedUserId = "someone-else"))
        assertFalse(token.matchesUser(verifiedUserId = "user-2", unverifiedUserId = null))
        assertTrue(token.matchesUser(verifiedUserId = null, unverifiedUserId = null))
        assertFalse(token.matchesUser(verifiedUserId = null, unverifiedUserId = "user-2"))
        assertFalse(token.matchesUser(verifiedUserId = null, unverifiedUserId = null, stagedUserId = "user-2"))
        // Canonically equivalent but differently encoded IDs are different users.
        assertFalse(IdentityToken("t", "é").matchesUser(verifiedUserId = "é", unverifiedUserId = null))
    }
}
