package sh.reception.client

import kotlinx.serialization.Serializable

/** The only persisted part of a device session. The access token stays in memory. */
@Serializable
internal data class SessionCredentials(val sessionSecret: String, val verifiedUserId: String? = null)
