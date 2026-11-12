package sh.reception

import sh.reception.ui.ChatPresenter
import sh.reception.ui.DeferredRestore

/** `Reception.openChat()` and `closeChat()` behind the facade; [ChatPresenter] presents and dismisses. Main thread only. */
internal object ReceptionChat {
    /**
     * `Reception.openChat()`, and the opens a push or a tap asks for: [stillWanted] is asked again right before the
     * sheet starts, so a request withdrawn while its record was being saved (routing turned off) does not appear.
     */
    fun open(stillWanted: () -> Boolean = { true }) {
        if (ReceptionState.session == null) return Log.error { "openChat called before configure, ignored" }
        Log.debug { "Chat presentation requested" }
        ChatPresenter.present(stillWanted)
    }

    /**
     * `Reception.closeChat()`: only the SDK's own sheet; a host-embedded chat is the host's to remove. It also withdraws
     * an automation's open that is still pending ([ReceptionState.closeRevision]) and, also before `configure`, a sheet
     * waiting to be restored ([DeferredRestore]).
     */
    fun close() {
        DeferredRestore.cancel("the chat was closed")
        if (ReceptionState.session == null) return Log.error { "closeChat called before configure, ignored" }
        ReceptionState.chatClosedByHost()
        if (!ChatPresenter.dismiss()) Log.debug { "Chat close skipped, chat not presented by the SDK" }
    }
}
