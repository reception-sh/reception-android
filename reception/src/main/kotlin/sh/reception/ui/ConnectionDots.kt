package sh.reception.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import sh.reception.Theme
import sh.reception.connectionDotAnimation
import sh.reception.muted

/** Three dots that rise one after another while the chat connects. */
@Composable
internal fun ConnectionDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "connection")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Theme.connectionDotSpacing),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(Theme.CONNECTION_DOT_COUNT) { index ->
            val lift by transition.animateFloat(0f, 1f, Theme.connectionDotAnimation(index), label = "dot")
            Box(Modifier.offset(y = -Theme.connectionDotLift * lift).size(Theme.connectionDotDiameter)
                .background(Theme.muted, CircleShape))
        }
    }
}
