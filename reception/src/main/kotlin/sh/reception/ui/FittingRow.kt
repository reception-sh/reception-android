package sh.reception.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * One row of children, vertically centered, when they fit side by side; otherwise a column aligned to the end, like
 * iOS's `ViewThatFits` over an `HStack` and a trailing `VStack`. Built on the plain `Layout` because `FlowRow`'s
 * signature changed after Compose 1.7 and a host on a newer Compose would not find the one the SDK compiled against.
 */
@Composable
internal fun FittingRow(rowSpacing: Dp, columnSpacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = rowSpacing.roundToPx()
        val rowWidth = measurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } + gap * (measurables.size - 1)
        val fits = measurables.isEmpty() || !constraints.hasBoundedWidth || rowWidth <= constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        if (fits) {
            val width = (placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0))
                .coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                var x = 0
                placeables.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + gap
                }
            }
        } else {
            val stackGap = columnSpacing.roundToPx()
            val width = (placeables.maxOf { it.width }).coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = (placeables.sumOf { it.height } + stackGap * (placeables.size - 1))
                .coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(width - it.width, y)
                    y += it.height + stackGap
                }
            }
        }
    }
}
