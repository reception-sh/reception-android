package sh.reception.client

import kotlinx.serialization.SerializationException

// The session credentials in secure storage. Every function blocks on the Keystore, so it runs on [Storage].

/** Storage thread. Throws when secure storage cannot be read: an unreadable session is not a missing one. */
internal fun DeviceStore.loadCredentials(): SessionCredentials? =
    when (val result = credentialStorage.read(values.key("session"))) {
        SecureStore.ReadResult.Missing -> null
        SecureStore.ReadResult.Unavailable -> throw ReceptionAPIError("secure_storage_unavailable", 0)
        is SecureStore.ReadResult.Value -> try {
            ReceptionAPI.json.decodeFromString(SessionCredentials.serializer(), result.value)
        } catch (_: SerializationException) {
            throw ReceptionAPIError("secure_storage_unavailable", 0)
        } catch (_: IllegalArgumentException) {
            throw ReceptionAPIError("secure_storage_unavailable", 0)
        }
    }

/** Storage thread. */
internal val DeviceStore.credentialsMissing: Boolean
    get() = credentialStorage.read(values.key("session")) == SecureStore.ReadResult.Missing

/** Storage thread. Throws unless the credentials reached the disk. */
internal fun DeviceStore.save(credentials: SessionCredentials) {
    val value = ReceptionAPI.json.encodeToString(SessionCredentials.serializer(), credentials)
    if (!credentialStorage.save(value, values.key("session"))) throw ReceptionAPIError("secure_storage_unavailable", 0)
}
