package sh.reception.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import sh.reception.Theme

/** A bubble without a tail; Compose caps the radius at half the height, so a one-line bubble is a capsule. */
internal val BubbleShape: Shape = RoundedCornerShape(Theme.bubbleRadius)
