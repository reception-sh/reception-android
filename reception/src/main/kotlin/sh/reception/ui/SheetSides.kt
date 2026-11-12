package sh.reception.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset

/**
 * The sheet's horizontal safe area. The surface keeps its width (the window's, up to the sheet's maximum) and sits
 * centered on the window; where that would reach under the display cutout or navigation bar at the window's sides, it
 * sits centered between them when it fits there, so a wide sheet never reaches a few pixels under an opaque side
 * navigation bar. Where it does not fit (a small phone in landscape, split screen) it reaches under them, as an iOS
 * sheet does, and its content is padded by the part of those insets that reaches into the sheet, so messages and the
 * composer never lie under them. [sheetWidth] measures the gaps between the
 * sheet's sides and the window's, which [sheetSideInsets] reads in the same layout pass: both sit in the sheet's one
 * modifier chain, so the gaps are measured right before the padding that uses them.
 */
internal class SheetSides {
    var left: Int = 0
    var right: Int = 0

    companion object {
        /**
         * The sheet's left edge in a window [windowWidth] wide: centered on the window when that keeps it clear of the
         * [insetLeft] and [insetRight] side insets, else centered between them, kept inside the window when it is wider
         * than the room between them.
         */
        fun left(windowWidth: Int, sheetWidth: Int, insetLeft: Int, insetRight: Int): Int {
            val centered = (windowWidth - sheetWidth) / 2
            if (centered >= insetLeft && centered + sheetWidth <= windowWidth - insetRight) return centered
            return (insetLeft + (windowWidth - insetLeft - insetRight - sheetWidth) / 2).coerceIn(0, windowWidth - sheetWidth)
        }
    }
}

/**
 * The sheet's width: the window's, at most [max], placed by [SheetSides.left] against the side [insets] (window pixels)
 * and taking the window's whole width, so the scrim beside it stays the sheet's sibling's to draw and touch.
 */
internal fun Modifier.sheetWidth(max: Dp, insets: WindowInsets, sides: SheetSides): Modifier = layout { measurable, constraints ->
    val width = constraints.constrainWidth(max.roundToPx())
    val windowWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else width
    sides.left = SheetSides.left(windowWidth, width, insets.getLeft(this, layoutDirection), insets.getRight(this, layoutDirection))
    sides.right = windowWidth - width - sides.left
    val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, width), maxWidth = width))
    layout(windowWidth, placeable.height) { placeable.place(sides.left, 0) }
}

/** Pads the sheet's content by the part of [insets] (window pixels) that reaches past the [sides]' gaps. */
internal fun Modifier.sheetSideInsets(insets: WindowInsets, sides: SheetSides): Modifier = layout { measurable, constraints ->
    val left = InsetOverlap.overlap(insets.getLeft(this, layoutDirection), sides.left, consumed = 0)
    val right = InsetOverlap.overlap(insets.getRight(this, layoutDirection), sides.right, consumed = 0)
    val placeable = measurable.measure(constraints.offset(horizontal = -(left + right)))
    layout(constraints.constrainWidth(placeable.width + left + right), placeable.height) { placeable.place(left, 0) }
}
