package sh.reception.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * appearance asks for it, the close button. The title bar is [Theme.titleBarHeight] tall and grows with a title a large
 * font scale makes taller, as the iOS title in its toolbar never clips; the history starts below it and
 * [onHeaderHeight] gets its height in pixels, top inset included. A reset rebuilds it with the new session's model. It is keyed by the
 * session's persisted chat identity, never an in-memory counter, so the saved keys of its result launchers (photo
 * picker, notification permission) match again after Android restarted the process for their result.
 */
@Composable
internal fun ChatContent(showsSheetControls: Boolean, onClose: () -> Unit = {}, onHeaderHeight: (Int) -> Unit = {}) {
    // Until the session's store is loaded (a few milliseconds after configure or a reset) only the background shows.
    val chatKey = ReceptionState.chatKey ?: return Box(Modifier.fillMaxSize().background(Theme.background))
    key(chatKey) {
        val model = remember { ReceptionState.chatModel() }
        ChatLifecycleEffect(model)
        // Custom Tabs opened from this chat take its accent.
        val accent = Theme.accent.toArgb()
        SideEffect { model.links.toolbarColor = accent }
        RemoteAppearanceRefresh()
        // The chat itself takes focus when the window hands it in (Android before 9, and any version outside touch
        // mode, gives a cleared focus straight to the first focusable view), so putting the keyboard away never moves
        // focus back into the composer, the only other focus target of an empty chat, which would raise it again.
        Box(Modifier.fillMaxSize().background(Theme.background).focusTarget()) {
            val top = if (showsSheetControls) Theme.sheetTopInset else Theme.zero
            val density = LocalDensity.current
            var headerHeight by remember { mutableStateOf(top + Theme.titleBarHeight) }
            val requestNotifications = rememberNotificationPermission(model)
            ChatHistory(model, topInset = headerHeight, requestNotifications)
            val header = if (showsSheetControls) Modifier.sheetHeader() else Modifier
            Box(
                Modifier.fillMaxWidth().then(header)
                    .onSizeChanged {
                        headerHeight = with(density) { it.height.toDp() }
                        onHeaderHeight(it.height)
                    }
                    .padding(top = top).heightIn(min = Theme.titleBarHeight),
            ) {
                val closes = showsSheetControls && Theme.appearance.showsCloseButton
                ChatTitle(closes, Modifier.align(Alignment.Center))
                if (closes) DismissButton(onClose, Modifier.align(Alignment.CenterEnd).padding(end = Theme.small))
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
