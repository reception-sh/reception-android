package sh.reception.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The limit behind a server wait, from the optional `error.retryScope` of a rejection. */
@Serializable
internal enum class RetryScope(val raw: String) {
    @SerialName("messages") MESSAGES("messages"),
    @SerialName("uploads") UPLOADS("uploads"),
    @SerialName("requests") REQUESTS("requests"),
    @SerialName("session") SESSION("session"),
    @SerialName("stream") STREAM("stream"),
    ;

    companion object {
        fun from(raw: String?): RetryScope? = entries.firstOrNull { it.raw == raw }

        /** The limit a route consumes. A `429` from an older service without a scope waits for it. */
        fun route(path: String, method: String): RetryScope? = when (method to endpoint(path)) {
            "POST" to "conversation/messages" -> MESSAGES
            "POST" to "uploads" -> UPLOADS
            "GET" to "conversation/events" -> STREAM
            "POST" to "devices", "POST" to "devices/me/token" -> SESSION
            else -> null
        }

        /**
         * The cooldowns that must be over before a request may reach the network: its route's own limit,
         * uploads for a message with photos, and the per-device request budget for bearer requests.
         */
        fun gates(path: String, method: String, authenticated: Boolean, photos: Boolean = false): List<RetryScope> {
            if (exempt(path, method)) return emptyList()
            val scopes = listOfNotNull(route(path, method)).toMutableList()
            if (photos) scopes.add(UPLOADS)
            if (authenticated) scopes.add(REQUESTS)
            return scopes
        }

        /** Account deletion and session revocation stay possible during every cooldown and never start one. */
        fun exempt(path: String, method: String): Boolean =
            (method == "DELETE" && endpoint(path) == "devices/me") ||
                (method == "POST" && endpoint(path) == "devices/me/logout")

        private fun endpoint(path: String): String = path.substringBefore('?')
    }
}
