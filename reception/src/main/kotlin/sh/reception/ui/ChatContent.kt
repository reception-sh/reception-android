package sh.reception.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.currentStateAsState
import sh.reception.ContrastMonitor
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionEvent
import sh.reception.ReceptionPaywalls
import sh.reception.ReceptionState
import sh.reception.ReceptionUnread
import sh.reception.Theme
import sh.reception.accent
import sh.reception.appearance
import sh.reception.background
import sh.reception.viewmodel.ChatModel
import sh.reception.viewmodel.activate
import sh.reception.viewmodel.deactivate

/**
 * The chat screen: title bar, history and composer. [showsSheetControls] adds the sheet's top inset and, when the
 * appearance asks for it, the close button. A reset rebuilds it with the new session's model.
 */
@Composable
internal fun ChatContent(showsSheetControls: Boolean, onClose: () -> Unit = {}) {
    key(ReceptionState.revision) {
        val model = remember { ReceptionState.chatModel() }
        ChatLifecycle(model)
        // Custom Tabs opened from this chat take its accent.
        val accent = Theme.accent.toArgb()
        SideEffect { model.links.toolbarColor = accent }
        RemoteAppearanceRefresh()
        Box(Modifier.fillMaxSize().background(Theme.background)) {
            val top = if (showsSheetControls) Theme.sheetTopInset else Theme.zero
            ChatHistory(model, topInset = top + Theme.titleBarHeight)
            Box(Modifier.fillMaxWidth().padding(top = top).height(Theme.titleBarHeight)) {
                ChatTitle(Modifier.align(Alignment.Center).semantics { heading() })
                if (showsSheetControls && Theme.appearance.showsCloseButton) {
                    DismissButton(onClose, Modifier.align(Alignment.CenterEnd).padding(end = Theme.small))
                }
            }
        }
    }
}

/**
 * Visibility follows the hosting lifecycle: started is on screen (`Reception.isChatOpen`), resumed is the active
 * chat that loads and listens, as iOS follows its scene's activity. A configuration change keeps both, and so does
 * the system photo picker ([sh.reception.viewmodel.ComposerModel.isPicking]).
 */
@Composable
private fun ChatLifecycle(model: ChatModel) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(owner, model) {
        var started = false
        var resumed = false
        fun appear() {
            if (started) return
            started = true
            ContrastMonitor.start(context)
            ReceptionPaywalls.refresh()
            if (!model.isVisible) ReceptionState.onEvent?.invoke(ReceptionEvent.ChatOpened)
            model.markVisible(true)
        }
        fun activate() {
            if (resumed) return
            resumed = true
            model.activate()
        }
        fun deactivate() {
            if (!resumed) return
            resumed = false
            model.deactivate(keepsPolling = context.isChangingConfigurations())
        }
        fun disappear() {
            deactivate()
            if (!started) return
            started = false
            ContrastMonitor.stop()
            if (context.isChangingConfigurations()) return
            model.markVisible(false)
            ReceptionUnread.refresh()
        }
        val observer = LifecycleEventObserver { _, event ->
            // The photo picker covers the chat like a sheet: no reload on return, the stream stays, the chat stays open.
            val covered = model.composer.isPicking
            when (event) {
                Lifecycle.Event.ON_START -> appear()
                Lifecycle.Event.ON_RESUME -> activate()
                Lifecycle.Event.ON_PAUSE -> if (!covered) deactivate()
                Lifecycle.Event.ON_STOP -> if (!covered) disappear()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            disappear()
        }
    }
}

/** The dashboard's settings from the latest fetch apply when the chat appears; fetching continues while it is active. */
@Composable
private fun RemoteAppearanceRefresh() {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = state.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(Unit) { ReceptionAppearance.configuration.activate() }
    LaunchedEffect(active, ReceptionAppearance.remoteEnabled) {
        if (active && ReceptionAppearance.remoteEnabled) ReceptionAppearance.configuration.refreshWhileActive()
    }
}

internal fun Context.isChangingConfigurations(): Boolean {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.isChangingConfigurations
        current = current.baseContext
    }
    return false
}
