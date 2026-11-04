package sh.reception.ui

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.delay
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.muted
import sh.reception.secondary
import sh.reception.strings.Strings

/** "Support is typing": three dots pulsing one after another in an incoming bubble, still when animations are off. */
@Composable
internal fun TypingIndicator(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val diameter = Theme.typingDotDiameter * density.fontScale
    val spacing = Theme.typingDotSpacing * density.fontScale
    val lift = with(density) { Theme.typingDotLift.toPx() }
    val lineHeight = with(density) { Theme.composerLineHeight.toDp() }
    val animates = remember { animatorsEnabled() }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    if (animates) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { elapsed = (it - start) / 1e9 }
                delay((Theme.TYPING_FRAME_INTERVAL * 1_000).toLong())
            }
        }
    }
    val label = ReceptionLocalization.string(Strings.supportIsTyping)
    Box(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label }) {
        Row(
            modifier = Modifier.background(Theme.secondary, BubbleShape).heightIn(min = lineHeight + Theme.bubbleVertical * 2)
                .padding(horizontal = Theme.bubbleHorizontal, vertical = Theme.bubbleVertical),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = Theme.muted
            repeat(Theme.CONNECTION_DOT_COUNT) { index ->
                Box(Modifier.size(diameter).graphicsLayer {
                    val pulse = if (animates) Theme.typingPulse(elapsed, index).toFloat() else 0f
                    alpha = Theme.TYPING_RESTING_OPACITY + pulse * Theme.TYPING_OPACITY_RANGE
                    scaleX = Theme.TYPING_RESTING_SCALE + pulse * Theme.TYPING_SCALE_RANGE
                    scaleY = scaleX
                    translationY = -pulse * lift
                }.background(color, CircleShape))
            }
        }
    }
}

/** False when the person turned animations off, the counterpart of Reduce Motion. */
private fun animatorsEnabled(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
