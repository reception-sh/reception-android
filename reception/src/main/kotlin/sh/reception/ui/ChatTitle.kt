package sh.reception.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sh.reception.Theme
import sh.reception.appearance
import sh.reception.foreground
import sh.reception.secondary
import sh.reception.titleFont

/** The title in a capsule, as iOS draws it over the scrolling history; hidden when the appearance hides it. */
@Composable
internal fun ChatTitle(modifier: Modifier = Modifier) {
    val appearance = Theme.appearance
    if (appearance.hidesTitle) return
    Text(
        text = appearance.title,
        style = Theme.titleFont,
        color = Theme.foreground,
        maxLines = 1,
        modifier = modifier.background(Theme.secondary, CircleShape)
            .padding(horizontal = Theme.titleChipHorizontal, vertical = Theme.titleChipVertical),
    )
}
