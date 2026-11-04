package sh.reception.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.Theme
import sh.reception.background
import sh.reception.foreground
import sh.reception.tertiary

/**
 * The chat in a full-height Material bottom sheet. With the keyboard up only a drag from the header may close it;
 * each touch decides that where it begins ([ChatPresenter.locksSheet]), and the decision holds until the sheet settles.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatSheet(dismissRequested: Boolean, onDismissed: () -> Unit) {
    ChatRoot(embedded = false) {
        var locked by remember { mutableStateOf(false) }
        val currentLock by rememberUpdatedState(locked)
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !currentLock })
        val scope = rememberCoroutineScope()
        val keyboardVisible by rememberUpdatedState(WindowInsets.isImeVisible)
        var release by remember { mutableStateOf<Job?>(null) }
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val close: () -> Unit = {
            locked = false
            scope.launch { state.hide() }.invokeOnCompletion { onDismissed() }
        }
        LaunchedEffect(dismissRequested) { if (dismissRequested) close() }
        ModalBottomSheet(
            onDismissRequest = onDismissed,
            // Full height below the status bar, like a sheet that leaves the app's top edge in view.
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
            sheetState = state,
            containerColor = Theme.background,
            contentColor = Theme.foreground,
            // The grabber is part of the content, so the header band below starts at the sheet's top edge.
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                val header = Theme.sheetHandleHeight.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    release?.cancel()
                    val onHeader = ChatPresenter.isSheetHandle(down.position.y, header)
                    locked = ChatPresenter.locksSheet(keyboardVisible, onHeader)
                    // With the keyboard up, the first drag would only put it away; closing it here lets one drag close.
                    if (keyboardVisible && onHeader) {
                        focus.clearFocus()
                        keyboard?.hide()
                    }
                    // The decision holds while the sheet settles from this drag, then scrim taps and Back close again.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    release = scope.launch {
                        delay(Theme.LAYOUT_SETTLE_MILLIS)
                        locked = false
                    }
                }
            }) {
                ChatContent(showsSheetControls = true, onClose = close)
                Grabber()
            }
        }
    }
}

/** The sheet's drag indicator in its top inset, above the title bar. */
@Composable
private fun Grabber() {
    Box(Modifier.fillMaxWidth().height(Theme.sheetTopInset), contentAlignment = Alignment.Center) {
        Box(Modifier.size(Theme.sheetGrabberWidth, Theme.sheetGrabberHeight).background(Theme.tertiary, CircleShape))
    }
}
