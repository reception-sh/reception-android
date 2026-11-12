package sh.reception.client

import sh.reception.Log

/**
 * The service's limits in UTF-16 units. Over-limit host values are dropped instead of sent, because a rejected
 * registration or device update would block loading and sending.
 */
internal object DeviceInputValidation {
    fun checked(value: String?, field: String, limit: Int): String? {
        if (value == null || value.length <= limit) return value
        Log.error { "identify: $field longer than $limit characters is not sent" }
        return null
    }

    fun checked(metadata: Map<String, String>): Map<String, String> {
        if (metadata.size > 20) {
            Log.error { "setMetadata: more than 20 entries, no metadata is sent" }
            return emptyMap()
        }
        val accepted = metadata.filter { it.key.length in 1..64 && it.value.length <= 512 }
        if (accepted.size < metadata.size) {
            Log.error { "setMetadata: entries need keys of 1 to 64 characters and values of up to 512; others are not sent" }
        }
        return accepted
    }
}
