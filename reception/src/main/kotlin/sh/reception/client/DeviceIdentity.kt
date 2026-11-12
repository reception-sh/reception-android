package sh.reception.client

import kotlinx.serialization.Serializable

/** Identity, metadata and push token staged for the next registration or device update. */
@Serializable
internal data class DeviceIdentity(
    val externalUserId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val pushToken: String? = null,
    val pushEnvironment: String? = null,
)
