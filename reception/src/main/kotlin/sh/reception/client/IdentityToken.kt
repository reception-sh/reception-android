package sh.reception.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okio.ByteString.Companion.decodeBase64
import kotlin.math.abs

/**
 * A token from `identify(token)`, kept in memory only. `userId` is read without verifying the signature;
 * the service verifies the token and applies the same `user_id` rules, so both sides compare the same string.
 */
internal data class IdentityToken(
    val value: String,
    val userId: String,
    /** Accepted by a registration or device update. Registrations always carry the token; updates only until then. */
    val sent: Boolean = false,
) {
    fun matchesUser(verifiedUserId: String?, unverifiedUserId: String?, stagedUserId: String? = null): Boolean {
        if (verifiedUserId != null) return UserIdentity.matches(userId, verifiedUserId)
        return (unverifiedUserId == null || UserIdentity.matches(unverifiedUserId, userId)) &&
            (stagedUserId == null || UserIdentity.matches(stagedUserId, userId))
    }

    companion object {
        private const val MAXIMUM_SAFE_INTEGER = 9_007_199_254_740_991.0

        fun parse(value: String): IdentityToken? = userId(value)?.let { IdentityToken(value, it) }

        fun userId(token: String): String? {
            if (token.length > 4096) return null
            val parts = token.split('.')
            if (parts.size != 3) return null
            val payload = parts[1].decodeBase64()?.utf8() ?: return null
            val claims = try {
                Json.parseToJsonElement(payload) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            return userId(claims["user_id"])
        }

        /** Strings are used as they are; a JSON integer within ±(2^53 − 1) becomes its decimal string. */
        private fun userId(claim: Any?): String? {
            val primitive = claim as? JsonPrimitive ?: return null
            if (primitive.isString) return primitive.content.takeIf { it.length in 1..255 }
            if (primitive.booleanOrNull != null) return null
            val number = primitive.content.toDoubleOrNull() ?: return null
            if (number.isNaN() || number.isInfinite() || number != Math.rint(number) || abs(number) > MAXIMUM_SAFE_INTEGER) {
                return null
            }
            return number.toLong().toString()
        }
    }
}
