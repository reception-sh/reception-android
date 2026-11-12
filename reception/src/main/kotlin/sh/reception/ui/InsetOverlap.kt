package sh.reception.ui

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far each edge of a chat sits inside the same edge of its window, in pixels; zero where it reaches or passes it. */
@Immutable
internal data class WindowEdges(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        /** A chat that covers its whole window, as the sheet does. */
        val Window = WindowEdges(0, 0, 0, 0)
    }
}

/**
 * Where a chat sits in its window: its [edges], and how many window pixels one of its own pixels covers horizontally
 * ([scaleX]) and vertically ([scaleY]), 1 unless the host scales it.
 */
@Immutable
internal data class ChatPlacement(val edges: WindowEdges, val scaleX: Float, val scaleY: Float) {
    companion object {
        /** A chat that covers its whole window unscaled, as the sheet does. */
        val Window = ChatPlacement(WindowEdges.Window, 1f, 1f)
    }
}

/**
 * The iOS rule for a view that avoids the keyboard and the system bars: a window inset counts only as far as it
 * reaches into the chat. An embedded chat above a tab bar gets only the part of the keyboard that covers it.
 */
internal object InsetOverlap {
    /** The edges of a chat at [left], [top], [right], [bottom] (window pixels) in a window of [width] × [height]. */
    fun edges(left: Float, top: Float, right: Float, bottom: Float, width: Int, height: Int): WindowEdges = WindowEdges(
        left = left.roundToInt().coerceAtLeast(0),
        top = top.roundToInt().coerceAtLeast(0),
        right = (width - right.roundToInt()).coerceAtLeast(0),
        bottom = (height - bottom.roundToInt()).coerceAtLeast(0),
    )

    /**
     * The part of an [inset] (from the window's edge) inside a chat whose edge sits [distance] inside the window's.
     * What an ancestor already [consumed] in Compose (`imePadding`, `consumeWindowInsets`) counts as handled too, so a
     * host that padded the chat up gets neither more padding than before nor the overlap subtracted twice.
     */
    fun overlap(inset: Int, distance: Int, consumed: Int): Int = (inset - maxOf(distance, consumed)).coerceAtLeast(0)

    /**
     * The chat's own pixels that cover [pixels] window pixels at [scale] window pixels per own pixel: an overlap
     * becomes padding inside a chat the host scales (a transition, a scaled container), so the padding still clears it.
     */
    fun local(pixels: Int, scale: Float): Int = (pixels / scale).roundToInt()

    /**
     * Window pixels per pixel of the chat along one of its axes, from the window vector its [length] pixels map to:
     * [along] that axis and [across] it. A chat that is rotated, mirrored or empty gets 1, the unscaled rule: padding
     * cannot follow a keyboard edge that is tilted against the chat, and window pixels are what it padded by before.
     */
    fun scale(along: Float, across: Float, length: Int): Float =
        if (length <= 0 || along <= 0f || abs(across) > AXIS_TOLERANCE) 1f else along / length

    /** How far, in window pixels, a chat's edge may lean off its axis and still count as unrotated. */
    private const val AXIS_TOLERANCE = 0.5f
}
