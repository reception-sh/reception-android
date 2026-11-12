package sh.reception.client

/**
 * Decides how each notification receipt and tap is handled so repeats act once: the same tap read from an activity's
 * creation and its resumption, a configuration change, an activity recreated with its old intent. There is one chat
 * per device, so every tap means "open the chat", and opening it again while it is presented changes nothing. Port of
 * the Flutter plugin's `NotificationRouting`.
 *
 * Keys are deduplicated exactly (the latest [CAPACITY] are remembered). A tap that arrived before configure waits as
 * the pending tap (latest wins) until the drain after configure opens the chat once; every tap arriving meanwhile is
 * recorded as opened and not routed. Afterwards taps route by key again.
 */
internal class NotificationRouting {
    enum class Action { RECEIVE, RECEIVE_AND_OPEN, OPEN, IGNORE }

    private class Tap(val key: String, val conversationId: String)

    private val opened = HashMap<String, Boolean>()
    private val order = ArrayDeque<String>()
    private var pending: Tap? = null

    val hasPendingTap: Boolean
        get() = pending != null

    /** Keeps a tap that arrived before configure; the latest wins. */
    fun queueTap(key: String, conversationId: String) {
        pending = Tap(key, conversationId)
    }

    /** Forgets the pending tap without opening anything. */
    fun discardPendingTap() {
        pending = null
    }

    /** Takes the pending tap and records its key; returns the conversation to open, or null if there was none. */
    fun drainPendingTap(): String? {
        val tap = pending ?: return null
        pending = null
        record(tap.key, openChat = true)
        return tap.conversationId
    }

    fun action(key: String, openChat: Boolean): Action {
        val previous = opened[key]
        if (previous == true || (previous == false && !openChat)) return Action.IGNORE
        record(key, openChat)
        // The pending tap's drain opens the chat; a tap before it would open it with the app's settings not yet applied.
        if (openChat && pending != null) return Action.IGNORE
        if (openChat) return if (previous == null) Action.RECEIVE_AND_OPEN else Action.OPEN
        return Action.RECEIVE
    }

    private fun record(key: String, openChat: Boolean) {
        if (key !in opened) {
            order.addLast(key)
            if (order.size > CAPACITY) opened.remove(order.removeFirst())
        }
        opened[key] = openChat
    }

    companion object {
        const val CAPACITY: Int = 64
    }
}
