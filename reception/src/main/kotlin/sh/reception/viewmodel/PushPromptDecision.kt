package sh.reception.viewmodel

import sh.reception.client.NotificationAuthorization
import sh.reception.configuration.PushPrompt

/** What the slot under the person's latest message shows. */
internal sealed interface PushPromptSlot {
    data class Card(val prompt: PushPrompt) : PushPromptSlot
    data object Status : PushPromptSlot
    data object None : PushPromptSlot
}

internal object PushPromptDecision {
    /**
     * The card asks once while the system has not asked yet, the status line confirms push once the service can reach
     * this device. Both step aside while that message failed or this device can't write, since neither can promise a
     * reply then; a message still sending keeps them in place, so they don't flicker with every send.
     */
    fun resolve(
        prompt: PushPrompt?,
        authorization: NotificationAuthorization?,
        declined: Boolean,
        answered: Boolean,
        lastSender: String?,
        lastFailed: Boolean,
        canWrite: Boolean,
        pushActive: Boolean?,
        hidesStatus: Boolean = false,
    ): PushPromptSlot {
        if (lastSender != "USER" || lastFailed || !canWrite) return PushPromptSlot.None
        if (prompt != null && authorization == NotificationAuthorization.NOT_DETERMINED && !declined && !answered) {
            return PushPromptSlot.Card(prompt)
        }
        if (!hidesStatus && authorization == NotificationAuthorization.AUTHORIZED && pushActive == true) {
            return PushPromptSlot.Status
        }
        return PushPromptSlot.None
    }
}
