package sh.reception.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import sh.reception.Theme
import sh.reception.background
import sh.reception.separator

/** The field's surface: a capsule, or a rounded rectangle once the text wraps, with a hairline edge. */
@Composable
internal fun Modifier.composerSurface(multiline: Boolean, radius: Dp): Modifier {
    val shape = if (multiline) RoundedCornerShape(radius) else CircleShape
    return background(Theme.background, shape).border(Dp.Hairline, Theme.separator, shape)
}

/** The photo button's circle. */
@Composable
internal fun Modifier.composerButtonSurface(): Modifier =
    background(Theme.background, CircleShape).border(Dp.Hairline, Theme.separator, CircleShape)
