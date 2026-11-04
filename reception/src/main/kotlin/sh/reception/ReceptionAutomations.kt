package sh.reception

import sh.reception.client.Automations
import sh.reception.client.PaidAccess
import sh.reception.client.SubscriptionEvents

/** `track`, paid access and store subscription events behind the facade. Main thread only. */
internal object ReceptionAutomations {
    val paidAccess: PaidAccess = PaidAccess()
    val automations: Automations = Automations(paidAccess = paidAccess)
    val subscriptions: SubscriptionEvents = SubscriptionEvents(automations)

    init {
        paidAccess.onChange = { ReceptionState.session?.let(subscriptions::changed) }
    }

    fun track(event: String, opensChat: Boolean) {
        val session = ReceptionState.session ?: return Log.error { "track called before configure, ignored" }
        if (!Automations.accepts(event)) {
            return Log.error { "track refused a malformed event name; use 1–64 letters, digits, underscores, dots or hyphens" }
        }
        automations.track(event, opensChat, session)
    }

    /**
     * Reads Play at configure and foreground: whether the person pays, for automations that skip or target subscribers,
     * and whether a subscription was cancelled or ran out.
     */
    fun checkSubscriptions() {
        val session = ReceptionState.session ?: return
        val context = session.store.context
        paidAccess.refresh(context)
        subscriptions.check(session) { paidAccess.refreshNow(context) }
    }
}
