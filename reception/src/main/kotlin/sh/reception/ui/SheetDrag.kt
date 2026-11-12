package sh.reception.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.Theme

/**
 * The sheet follows a drag on itself, and a drag on the message list once the list cannot scroll further, like
 * Material's sheet: an upward scroll first moves a lowered sheet back up, and a release settles it. A touch catches a
 * sliding sheet at once, without waiting for the touch slop. Each touch decides where it begins whether it may close
 * the sheet ([SheetGestures.locksSheet]); the decision holds until the sheet has settled from that touch.
 */
@Composable
internal fun Modifier.sheetDrag(state: SheetState): Modifier {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Job?>(null) }
    val connection = remember(state) { SheetScrollConnection(state) }
    return nestedScroll(connection)
        .draggable(state.draggable, Orientation.Vertical, enabled = !state.closing,
            startDragImmediately = state.isSliding, onDragStopped = { velocity -> state.settle(velocity) })
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                release?.cancel()
                val header = state.headerHeight.takeIf { it > 0 }?.toFloat() ?: Theme.sheetHandleHeight.toPx()
                val onHeader = SheetGestures.isSheetHandle(down.position.y, header)
                val keyboardShown = view.keyboardShown()
                state.locked = SheetGestures.locksSheet(keyboardShown, onHeader)
                // With the keyboard up, the first drag would only put it away; closing it here lets one drag close.
                if (keyboardShown && onHeader) {
                    focus.clearFocus()
                    keyboard?.hide()
                }
                // The decision holds while the sheet settles from this touch, then scrim taps and Back close again.
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                release = scope.launch {
                    delay(Theme.LAYOUT_SETTLE_MILLIS)
                    state.locked = false
                }
            }
        }
}

/**
 * The sheet's header (grabber inset and title bar) takes its own touches: the message list is drawn beneath it, and
 * would otherwise scroll for a drag that starts there. Nothing is consumed, so [sheetDrag] on the sheet moves it, as a
 * drag on Material's drag handle or on the iOS sheet's navigation bar does.
 */
internal fun Modifier.sheetHeader(): Modifier = pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
}

/**
 * Material's `ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection`, for the open/hidden sheet. A moved sheet
 * settles when the list's drag ends, whatever its velocity, and keeps that velocity, so no list fling (which a touch can
 * cancel before its end) stands between the release and the settle.
 */
internal class SheetScrollConnection(private val state: SheetState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && source == NestedScrollSource.UserInput) Offset(0f, state.moveBy(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.moveBy(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (state.offset <= 0f) return Velocity.Zero
        state.settle(available.y)
        return available
    }
}
