package sh.reception

import kotlinx.coroutines.Job
import sh.reception.client.DeviceSession
import sh.reception.client.DeviceStore
import sh.reception.client.ReceptionAPIError
import sh.reception.client.Storage
import sh.reception.client.recordFailure

/**
 * The ends of a session short of a new configure: logout, deletion, a revoked session and the team's chat reset. Each
 * replaces the current session with a fresh one ([ReceptionState.replaceSession]). Main thread only.
 */
internal object ReceptionResets {
    /** Ends the session in memory at once; its stored values are deleted on the storage thread after [after]. */
    fun resetLocal(dismissChat: Boolean = true, after: Job? = null) {
        ReceptionState.unreadMonitor.setActive(false)
        ReceptionUnread.cancelRefresh()
        val session = ReceptionState.session ?: return
        val namespace = ReceptionState.namespace ?: return
        val context = session.store.context
        val deleted = session.invalidate(ReceptionState.storageWork, after)
        ReceptionState.stopChat()
        // The deletion rotates the generation, so the new session opens a fresh namespace after it.
        val next = DeviceSession(session.api, DeviceStore(context, namespace), halt = session.halt, after = deleted)
        ReceptionState.replaceSession(next, storage = deleted)
        ReceptionUnread.set(0)
        if (dismissChat) ReceptionState.advancePresentationRevision()
    }

    fun resetChat(revision: Int, deviceDeleted: Boolean = false) {
        val session = ReceptionState.session ?: return
        if (deviceDeleted) {
            val identity = session.pending
            resetLocal(dismissChat = false)
            ReceptionState.session?.stage { identity }
            return
        }
        ReceptionState.unreadMonitor.setActive(false)
        ReceptionUnread.cancelRefresh()
        session.deactivate()
        ReceptionState.stopChat()
        session.store.resetChat(revision)
        val store = session.store
        val cleared = Storage.launch(ReceptionState.storageWork) { store.clearChatFiles() }
        ReceptionState.replaceSession(DeviceSession(session.api, store.renewed(), after = cleared), storage = cleared)
        ReceptionUnread.set(0)
    }

    /** A `409 chat_reset` from the current session clears the chat and keeps the session. */
    fun handleReset(session: DeviceSession, error: Throwable): Boolean {
        if (session !== ReceptionState.session || error !is ReceptionAPIError) return false
        session.recordFailure(error)
        val resetRevision = error.resetRevision
        if (error.status != 409 || error.code != "chat_reset" || resetRevision == null || resetRevision < 0) return false
        Log.info { "Chat reset by the team, local history cleared, revision $resetRevision" }
        resetChat(resetRevision)
        return true
    }
}
