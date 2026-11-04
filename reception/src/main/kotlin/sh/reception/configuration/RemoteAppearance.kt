package sh.reception.configuration

import kotlinx.serialization.Serializable

/** `GET /v1/appearance`: the published document and the push prompt setting for this platform. */
@Serializable
internal class AppearanceEnvelope(
    val schemaVersion: Int,
    val revision: Long,
    val appearance: RemoteAppearance,
    /** Absent from older services and older cached envelopes. */
    val pushPrompt: String? = null,
) {
    /** Schema 1 with a nonnegative revision; anything else keeps the last compatible snapshot. */
    val isCompatible: Boolean
        get() = schemaVersion == 1 && revision >= 0
}

/**
 * The dashboard's published appearance (schema 1, `REMOTE_APPEARANCE.md`). Every field is optional. Unknown fields
 * are ignored and invalid strings fall back locally, but a value of the wrong JSON type rejects the whole envelope.
 */
@Serializable
internal class RemoteAppearance(
    val colors: Map<String, AdaptiveAppearanceColor>? = null,
    val text: Map<String, Map<String, String>>? = null,
    val colorScheme: String? = null,
    val showsCloseButton: Boolean? = null,
    val closeIcon: String? = null,
    val closeImage: String? = null,
    val hidden: List<String>? = null,
    val visibility: Map<String, Boolean>? = null,
    val fontDesign: String? = null,
    val fadesOlderMessages: Boolean? = null,
    val showsTeamPhotos: Boolean? = null,
    val showsTeamNames: Boolean? = null,
    val welcomeIcon: RemoteIcon? = null,
    val cards: RemoteCards? = null,
) {
    @Serializable
    class RemoteIcon(
        val symbol: String? = null,
        val image: String? = null,
        val color: AdaptiveAppearanceColor? = null,
        val size: Double? = null,
        val cornerRadius: Double? = null,
        val offset: RemoteOffset? = null,
    )

    @Serializable
    class RemoteOffset(val x: Double, val y: Double)

    @Serializable
    class RemoteCards(val review: RemoteCard? = null, val offer: RemoteCard? = null, val link: RemoteCard? = null)

    @Serializable
    class RemoteCard(
        val icon: String? = null,
        val iconImage: String? = null,
        val accessory: String? = null,
        val accessoryImage: String? = null,
        val button: AdaptiveAppearanceColor? = null,
        val buttonText: AdaptiveAppearanceColor? = null,
        val iconSize: Double? = null,
    )
}

/** The dashboard's "Ask for push notifications" setting: which consent text the in-chat card shows. */
internal enum class PushPrompt(val raw: String) {
    REPLIES("replies"), OFFERS("offers");

    companion object {
        fun from(raw: String?): PushPrompt? = entries.firstOrNull { it.raw == raw }
    }
}
