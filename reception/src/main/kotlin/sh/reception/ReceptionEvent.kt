package sh.reception

/** What happened in the chat, delivered to `Reception.onEvent` on the main thread. */
public sealed class ReceptionEvent {
    /** The chat became visible. */
    public data object ChatOpened : ReceptionEvent()

    /** A message the person wrote reached the service, including a photo-only message. */
    public data object MessageSent : ReceptionEvent()

    /** The confirmed message contains photos; [MessageSent] follows too. */
    public data object ImageSent : ReceptionEvent()

    /** The person opened the review link; this does not prove a review was submitted. */
    public data object ReviewOpened : ReceptionEvent()

    /** The person opened a paywall card; this does not prove presentation or purchase. */
    public data object PaywallOpened : ReceptionEvent()

    /** The person tapped a link card's button; this does not prove the destination opened. */
    public data object LinkOpened : ReceptionEvent()

    /** `deleteData()` completed, including a local-only completion. */
    public data object DataDeleted : ReceptionEvent()

    /** The service rejected the token from `identifyWithToken`. */
    public data class IdentityRejected(public val error: ReceptionError) : ReceptionEvent()
}
