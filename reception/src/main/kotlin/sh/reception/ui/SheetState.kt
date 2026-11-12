package sh.reception.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import sh.reception.Theme
import sh.reception.sheetAnimation
import sh.reception.sheetCloseDistance
import sh.reception.sheetCloseVelocity

/**
 * Where the chat sheet is and where it goes: [hidden] runs from 0 (open) to 1 (below the window), so a rotation or a
 * new height keeps the sheet where it was. Drags, the enter animation and settling share [draggable]'s mutex, and a
 * nested scroll stops a running slide before it moves the sheet, so the position has one writer at a time; a touch
 * catches a sliding sheet ([isSliding]). A close runs at a priority no touch interrupts and calls [onDismissed] exactly
 * once, after the sheet left the screen. While [locked], the person's own closing gestures (drag, scrim, back) leave
 * it open.
 */
@Stable
internal class SheetState(
    private val scope: CoroutineScope,
    open: Boolean,
    private val closeDistance: Float,
    private val closeVelocity: Float,
    private val onDismissed: () -> Unit,
) {
    var hidden by mutableFloatStateOf(if (open) 0f else 1f)
        private set

    /** The sheet's height in pixels, from its layout. */
    var height by mutableIntStateOf(0)

    /**
     * The height of the sheet's header (grabber inset and title bar) in pixels, from its layout; 0 until measured. A
     * touch that starts in it starts on the sheet's handle. The title bar grows with a large font scale.
     */
    var headerHeight by mutableIntStateOf(0)

    var locked by mutableStateOf(false)

    var closing by mutableStateOf(false)
        private set

    /** Predictive back's eased progress, 0…1. */
    val back = Animatable(0f)

    /** Pixels below the open position. */
    val offset: Float get() = hidden * height

    /**
     * Whether the sheet has reached its open position; a new sheet has not until its enter animation or a drag brings it
     * there. A release is judged from here, as Material's sheet judges it from its current value.
     */
    private var reachedOpen = open

    /** The running enter or settle animation, the one motion a touch or a nested scroll may stop. */
    private var slide by mutableStateOf<Job?>(null)

    /** Whether the sheet slides in or settles back open: a touch on it then catches it at once. */
    val isSliding: Boolean get() = slide != null

    val draggable = DraggableState { delta -> moveBy(delta) }

    /**
     * Moves the sheet by [delta] pixels within open…hidden and returns the pixels it moved. A drag or a nested scroll
     * that moves the sheet stops its slide first; nothing moves it while it closes.
     */
    fun moveBy(delta: Float): Float {
        if (height == 0 || closing || delta == 0f) return 0f
        stopSlide()
        val before = offset
        hidden = ((before + delta) / height).coerceIn(0f, 1f)
        if (hidden == 0f) reachedOpen = true
        return offset - before
    }

    suspend fun show() {
        slideOpen(velocity = 0f)
    }

    /**
     * A release at [velocity] pixels per second: closes when Material's rule says so ([SheetGestures.closesOnRelease])
     * unless locked, else slides open. Wherever a touch or a scroll left the sheet, it comes to rest open or closed.
     */
    fun settle(velocity: Float) {
        if (closing) return
        val closes = SheetGestures.closesOnRelease(offset, velocity, height.toFloat(), reachedOpen, closeDistance,
            closeVelocity)
        when {
            closes && !locked -> close(velocity)
            hidden > 0f -> scope.launch { slideOpen(velocity) }
        }
    }

    /** The person asks to close (scrim, back, accessibility): refused while locked. */
    fun requestClose(): Boolean {
        if (locked || closing) return false
        close()
        return true
    }

    /** Slides the sheet away whatever the lock, then calls [onDismissed]; later calls do nothing. */
    fun close(velocity: Float = 0f) {
        if (closing) return
        closing = true
        locked = false
        scope.launch { draggable.drag(MutatePriority.PreventUserInput) { animateTo(1f, velocity) } }
            .invokeOnCompletion { onDismissed() }
    }

    /** A cancelled back gesture: the sheet grows back to its size. */
    fun cancelBack() {
        scope.launch { back.animateTo(0f) }
    }

    /** Slides open as the one writer of the position until it arrives, or a touch, a scroll or a close stops it. */
    private suspend fun slideOpen(velocity: Float) {
        draggable.drag(MutatePriority.Default) {
            val job = coroutineContext.job
            slide = job
            try {
                animateTo(0f, velocity)
                reachedOpen = true
            } finally {
                if (slide === job) slide = null
            }
        }
    }

    /** Ends the slide on the spot, before its next frame; its coroutine ends as a drag's preemption would end it. */
    private fun stopSlide() {
        slide?.cancel()
        slide = null
    }

    private suspend fun animateTo(target: Float, velocity: Float) {
        val pixels = height.coerceAtLeast(1)
        animate(hidden, target, velocity / pixels, Theme.sheetAnimation()) { value, _ -> hidden = value }
    }
}

/**
 * The sheet's state for this activity. A sheet that was shown before (a rotation, or Android restoring it after the
 * process died) comes back open without the enter animation, and so does one with [opensOpen] (a sheet that comes back
 * after a launcher return finished its predecessor); a new one starts hidden and slides in.
 */
@Composable
internal fun rememberSheetState(onDismissed: () -> Unit, opensOpen: Boolean = false): SheetState {
    var presented by rememberSaveable { mutableStateOf(opensOpen) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissed by rememberUpdatedState(onDismissed)
    val state = remember {
        SheetState(scope, open = presented, with(density) { Theme.sheetCloseDistance.toPx() },
            with(density) { Theme.sheetCloseVelocity.toPx() }, onDismissed = { dismissed() })
    }
    LaunchedEffect(state) {
        if (presented) return@LaunchedEffect
        presented = true
        state.show()
    }
    return state
}
