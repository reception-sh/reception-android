package sh.reception.model

import kotlinx.serialization.Serializable

@Serializable
internal data class Attachment(
    val id: String,
    val url: String,
    val contentType: String,
    val width: Int? = null,
    val height: Int? = null,
)
