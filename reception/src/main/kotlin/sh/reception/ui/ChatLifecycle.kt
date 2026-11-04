package sh.reception.ui

import android.content.Context
import androidx.lifecycle.Lifecycle
import sh.reception.ContrastMonitor
import sh.reception.ReceptionEvent
import sh.reception.ReceptionPaywalls
import sh.reception.ReceptionState
import sh.reception.ReceptionUnread
import sh.reception.viewmodel.ChatModel
import sh.reception.viewmodel.activate
import sh.reception.viewmodel.deactivate

/**
 * Visibility follows the hosting lifecycle: started is on screen (`Reception.isChatOpen`), resumed is the active
 * chat that loads and listens, as iOS follows its scene's activity. A configuration change keeps both, and so does
 * the system photo picker ([sh.reception.viewmodel.ComposerModel.isPicking]), which only pauses the chat's activity.
 * The app going to the background ends both even under the picker; the chat's next start and resume begin them again.
 */
internal class ChatLifecycle(
    private val model: ChatModel,
    private val context: Context,
    private val isChangingConfigurations: () -> Boolean = { context.isChangingConfigurations() },
) {
    private var started = false
    private var resumed = false

    /** An event of the hosting lifecycle. The photo picker covers the chat like a sheet: no reload, the stream stays. */
    fun hostChanged(event: Lifecycle.Event) {
        val covered = model.composer.isPicking
        when (event) {
            Lifecycle.Event.ON_START -> appear()
            Lifecycle.Event.ON_RESUME -> activate()
            Lifecycle.Event.ON_PAUSE -> if (!covered) deactivate()
            Lifecycle.Event.ON_STOP -> if (!covered) disappear()
            else -> Unit
        }
    }

    /**
     * Home or the lock screen, picker or not. The model stops unconditionally: a chat recreated under the picker by a
     * rotation never resumed, yet its predecessor handed it a running stream, monitor and retries.
     */
    fun backgrounded() {
        resumed = false
        model.deactivate(keepsPolling = false)
        if (started) {
            started = false
            ContrastMonitor.stop()
        }
        if (!model.isVisible) return
        model.markVisible(false)
        ReceptionUnread.refresh()
    }

    /** The chat leaves the composition. */
    fun disposed() = disappear()

    private fun appear() {
        if (started) return
        started = true
        ContrastMonitor.start(context)
        ReceptionPaywalls.refresh()
        if (!model.isVisible) ReceptionState.onEvent?.invoke(ReceptionEvent.ChatOpened)
        model.markVisible(true)
    }

    private fun activate() {
        if (resumed) return
        resumed = true
        model.activate()
    }

    private fun deactivate() {
        if (!resumed) return
        resumed = false
        model.deactivate(keepsPolling = isChangingConfigurations())
    }

    private fun disappear() {
        deactivate()
        if (!started) return
        started = false
        ContrastMonitor.stop()
        if (isChangingConfigurations()) return
        model.markVisible(false)
        ReceptionUnread.refresh()
    }
}
