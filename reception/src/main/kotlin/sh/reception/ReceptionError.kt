package sh.reception

/**
 * A failed server request from `Reception.deleteData()`, where local data is retained for retrying, or an identity
 * token the service rejected (see [ReceptionEvent.IdentityRejected]).
 */
public data class ReceptionError(
    public val code: String,
    /** HTTP status, or zero when no valid response was received. */
    public val status: Int,
) : Exception("$code ($status)")
