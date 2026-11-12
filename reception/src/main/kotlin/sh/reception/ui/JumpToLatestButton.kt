package sh.reception.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import sh.reception.R
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.accent
import sh.reception.background
import sh.reception.foreground
import sh.reception.jumpToLatestShadow
import sh.reception.messageAnimation
import sh.reception.separator
import sh.reception.strings.Strings
import sh.reception.subheadlineFont
import sh.reception.viewmodel.ChatScrollState

/** The control rising into view above the composer while replies wait below the reader. */
@Composable
internal fun JumpToLatest(visible: Boolean, unseenCount: Int, modifier: Modifier = Modifier, action: () -> Unit) {
    val rise = with(LocalDensity.current) { Theme.jumpToLatestRise.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Theme.messageAnimation()) + slideInVertically(Theme.messageAnimation()) { rise },
        exit = fadeOut(Theme.messageAnimation()) + slideOutVertically(Theme.messageAnimation()) { rise },
        modifier = modifier.padding(bottom = Theme.jumpToLatestGap),
    ) {
        JumpToLatestButton(unseenCount, action)
    }
}

/** Floats above the composer when replies arrived below a reader who is further up: "⌄ 3 new messages". */
@Composable
internal fun JumpToLatestButton(unseenCount: Int, action: () -> Unit, modifier: Modifier = Modifier) {
    val scale = minOf(LocalDensity.current.fontScale, Theme.JUMP_TO_LATEST_MAXIMUM_SCALE)
    val label = ReceptionLocalization.string(Strings.jumpToLatestMessage)
    val count = ReceptionLocalization.plural(Strings.Plurals.newMessages, unseenCount)
    Box(
        modifier = modifier.sizeIn(minWidth = Theme.jumpToLatestTarget, minHeight = Theme.jumpToLatestTarget)
            .clickable(role = Role.Button, onClick = action)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = count
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.heightIn(min = Theme.jumpToLatestHeight * scale)
                .shadow(Theme.jumpToLatestShadowRadius, CircleShape, ambientColor = Theme.jumpToLatestShadow,
                    spotColor = Theme.jumpToLatestShadow)
                .background(Theme.background, CircleShape).border(Dp.Hairline, Theme.separator, CircleShape)
                .padding(horizontal = Theme.jumpToLatestHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Theme.jumpToLatestLabelSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.reception_symbol_keyboard_arrow_down), contentDescription = null,
                tint = Theme.accent, modifier = Modifier.size(Theme.jumpToLatestIcon * scale))
            val text = ChatScrollState.unreadText(unseenCount) { ReceptionLocalization.plural(Strings.Plurals.newMessages, it) }
            Text(text ?: "", style = Theme.subheadlineFont.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR),
                color = Theme.foreground, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

private const val TABULAR = "tnum"
