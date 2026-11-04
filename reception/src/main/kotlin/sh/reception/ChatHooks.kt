package sh.reception

/**
 * The chat model's side of the facade, as iOS reaches `Reception.shared.chat`. The session and unread code call these;
 * the chat model implements them. Without a chat model (`ReceptionState.chat` is null) there is nothing to update.
 */
internal interface ChatHooks {
    val isVisible: Boolean

    /** A probe through a halted session succeeded, so the device may send again. */
    fun sendingResumed()

    /** The team wrote first: an open chat starts loading and listening. */
    fun conversationStarted()

    fun suspendAutomaticRequests()

    fun retryFailedAutomatically()

    fun startStream()

    fun cooldownsChanged()

    fun clearVerificationRequirement()

    /** Ends the polling, stream, sends and timers of a chat whose session ended. */
    fun stop()
}
