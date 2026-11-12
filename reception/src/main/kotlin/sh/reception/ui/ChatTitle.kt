package sh.reception.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import sh.reception.Theme
import sh.reception.appearance
import sh.reception.foreground
import sh.reception.secondary
import sh.reception.titleFont

/**
 * The title in a capsule, as iOS draws it over the scrolling history; hidden when the appearance hides it. One line,
 * centered, and a title too long for it ends in an ellipsis, as in iOS's navigation bar; it never reaches under the
 * close button ([titleSideInset]).
 */
@Composable
internal fun ChatTitle(showsCloseButton: Boolean, modifier: Modifier = Modifier) {
    val appearance = Theme.appearance
    if (appearance.hidesTitle) return
    Text(
        text = appearance.title,
        style = Theme.titleFont,
        color = Theme.foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = titleSideInset(showsCloseButton))
            .semantics { heading() }
            .background(Theme.secondary, CircleShape)
            .padding(horizontal = Theme.titleChipHorizontal, vertical = Theme.titleChipVertical),
    )
}

/**
 * The room the centered title leaves on each side of the title bar: the close button with its margin to the edge and
 * a gap before the title, on both sides so the title stays centered; else the margin alone.
 */
internal fun titleSideInset(showsCloseButton: Boolean): Dp =
    if (showsCloseButton) Theme.small + Theme.dismissDiameter + Theme.small else Theme.small
