package sh.reception.client

internal object UserIdentity {
    /** User IDs are opaque server identifiers, compared by their UTF-8 bytes without Unicode normalization. */
    fun matches(first: String?, second: String?): Boolean {
        if (first == null || second == null) return first == null && second == null
        return first.toByteArray(Charsets.UTF_8).contentEquals(second.toByteArray(Charsets.UTF_8))
    }
}
