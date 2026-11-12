package sh.reception.client

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.reception.Log
import sh.reception.ReceptionState

/**
 * Store subscriptions for automations: cancelled or expired subscriptions become events, and a change of paid access
 * reaches the service at once. Main thread only.
 */
internal class SubscriptionEvents(private val automations: Automations) {
    private var checking = false
    private var checkedAt: Long? = null

    /**
     * Reports a cancelled or expired subscription Play shows since the last check. Runs when the app opens, at most
     * once a minute, and only while an automation listens.
     */
    fun check(session: DeviceSession, purchases: suspend () -> List<PlayBillingBridge.Purchase>?) {
        checkedAt?.let { if (automations.clock() - it < CHECK_INTERVAL_MILLIS) return }
        if (session.halt != null || checking) return
        checking = true
        checkedAt = automations.clock()
        automations.scope.launch {
            try {
                if (!automations.listens(StoreSubscriptions.events, session)) return@launch
                val current = purchases() ?: return@launch
                try {
                    session.prepared()
                } catch (_: ReceptionAPIError) {
                    return@launch
                }
                if (session !== ReceptionState.session) return@launch
                val known = session.store.subscriptionStates.toMutableMap()
                for ((group, state) in StoreSubscriptions.states(known, current)) {
                    val event = StoreSubscriptions.event(SubscriptionState.from(known[group]), state)
                    if (event != null) {
                        val start = automations.clock()
                        val presentation = ReceptionState.presentationRevision
                        val closes = ReceptionState.closeRevision
                        val delivery = automations.deliver(event, session)
                        // An undelivered change keeps the old state, so the next check reports it again.
                        if (delivery == Automations.Delivery.Retry) continue
                        Log.debug { "Subscription change reported as $event" }
                        if (delivery == Automations.Delivery.Received(openChat = true) && session.halt == null) {
                            automations.openChatWhenSettled(start, presentation, closes, arriving = true)
                        }
                    }
                    known[group] = state.raw
                }
                if (session === ReceptionState.session) session.store.subscriptionStates = known
            } finally {
                checking = false
            }
        }
    }

    /**
     * A purchase must reach the service before a delayed automation offers a discount, so a registered device sends
     * a change of paid access or `subscription_status` at once instead of with its next update.
     */
    fun changed(session: DeviceSession) {
        if (session.halt != null || !session.isPrepared || !session.isRegistered) return
        automations.scope.launch {
            try {
                withContext(RequestContext(automation = true)) { session.update() }
            } catch (_: ReceptionAPIError) {
                Log.debug { "Subscription change not delivered, it follows with the next update" }
            }
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MILLIS = 60_000L
    }
}
