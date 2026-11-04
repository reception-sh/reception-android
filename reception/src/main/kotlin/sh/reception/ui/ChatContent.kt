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
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionState
import sh.reception.Theme
import sh.reception.accent
import sh.reception.appearance
import sh.reception.background
import sh.reception.viewmodel.ChatModel

/**
 * The chat screen: title bar, history and composer. [showsSheetControls] adds the sheet's top inset and, when the
 * appearance asks for it, the close button. A reset rebuilds it with the new session's model. It is keyed by the
 * session's persisted chat identity, never an in-memory counter, so the saved keys of its result launchers (photo
 * picker, notification permission) match again after Android restarted the process for their result.
 */
@Composable
internal fun ChatContent(showsSheetControls: Boolean, onClose: () -> Unit = {}) {
    // Until the session's store is loaded (a few milliseconds after configure or a reset) only the background shows.
    val chatKey = ReceptionState.chatKey ?: return Box(Modifier.fillMaxSize().background(Theme.background))
    key(chatKey) {
        val model = remember { ReceptionState.chatModel() }
        ChatLifecycleEffect(model)
        // Custom Tabs opened from this chat take its accent.
        val accent = Theme.accent.toArgb()
        SideEffect { model.links.toolbarColor = accent }
        RemoteAppearanceRefresh()
        Box(Modifier.fillMaxSize().background(Theme.background)) {
            val top = if (showsSheetControls) Theme.sheetTopInset else Theme.zero
            val requestNotifications = rememberNotificationPermission(model)
            ChatHistory(model, topInset = top + Theme.titleBarHeight, requestNotifications)
            Box(Modifier.fillMaxWidth().padding(top = top).height(Theme.titleBarHeight)) {
                ChatTitle(Modifier.align(Alignment.Center).semantics { heading() })
                if (showsSheetControls && Theme.appearance.showsCloseButton) {
                    DismissButton(onClose, Modifier.align(Alignment.CenterEnd).padding(end = Theme.small))
                }
            }
        }
    }
}

/** Feeds the hosting and the process lifecycle to [ChatLifecycle]. */
@Composable
private fun ChatLifecycleEffect(model: ChatModel) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(owner, model) {
        val lifecycle = ChatLifecycle(model, context)
        val observer = LifecycleEventObserver { _, event -> lifecycle.hostChanged(event) }
        // The picker leaves the app in the foreground; Home or the lock screen do not. On return the chat's own start
        // shows it again, and its resume (the picker closed) loads and listens again.
        val application = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) lifecycle.backgrounded()
        }
        val process = ProcessLifecycleOwner.get().lifecycle
        owner.lifecycle.addObserver(observer)
        process.addObserver(application)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            process.removeObserver(application)
            lifecycle.disposed()
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

internal fun Context.isChangingConfigurations(): Boolean = findActivity()?.isChangingConfigurations == true

/** The activity behind a Compose context, which may be wrapped. */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
