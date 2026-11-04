package sh.reception.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.bodyFont
import sh.reception.captionFont
import sh.reception.cardButton
import sh.reception.cardButtonText
import sh.reception.configuration.AppearanceIcon
import sh.reception.configuration.AppearanceIconView
import sh.reception.configuration.ResolvedAppearance
import sh.reception.incomingForeground
import sh.reception.muted
import sh.reception.secondary
import sh.reception.strings.Strings

/**
 * A card with the agent's text above a full-width button: icon, label and accessory, in the card style's colors.
 * Without text the button stands alone instead of sitting in an empty card.
 */
@Composable
internal fun ActionCard(
    text: String,
    label: String,
    symbol: String,
    accessory: String,
    style: ResolvedAppearance.CardStyle,
    isAvailable: Boolean = true,
    action: () -> Unit,
) {
    if (text.isEmpty()) {
        CardContent(text, label, symbol, accessory, style, isAvailable, action)
    } else {
        Box(Modifier.background(Theme.secondary, BubbleShape).padding(horizontal = Theme.bubbleHorizontal, vertical = Theme.bubbleVertical)) {
            CardContent(text, label, symbol, accessory, style, isAvailable, action)
        }
    }
}

@Composable
private fun CardContent(
    text: String,
    label: String,
    symbol: String,
    accessory: String,
    style: ResolvedAppearance.CardStyle,
    isAvailable: Boolean,
    action: () -> Unit,
) {
    // The built-in size scales with the font like the label; a card style's size scales the same way.
    val iconSize = (style.iconSize ?: Theme.cardIconSize) * LocalDensity.current.fontScale
    val foreground = Theme.cardButtonText(style)
    Column(verticalArrangement = Arrangement.spacedBy(Theme.small)) {
        if (text.isNotEmpty()) Text(text, style = Theme.bodyFont, color = Theme.incomingForeground)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = Theme.control).clip(BubbleShape)
                .background(Theme.cardButton(style, isAvailable))
                .clickable(enabled = isAvailable, role = Role.Button, onClick = action)
                .semantics { contentDescription = label }
                .padding(horizontal = Theme.bubbleHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Theme.small, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppearanceIconView(style.icon ?: AppearanceIcon.Symbol(symbol), iconSize, foreground, symbol,
                Modifier.clearAndSetSemantics {})
            Text(label, style = Theme.bodyFont.copy(fontWeight = FontWeight.SemiBold), color = foreground,
                textAlign = TextAlign.Center, modifier = Modifier.clearAndSetSemantics {})
            AppearanceIconView(style.accessory ?: AppearanceIcon.Symbol(accessory), iconSize, foreground, accessory,
                Modifier.clearAndSetSemantics {})
        }
        if (!isAvailable) {
            Text(ReceptionLocalization.string(Strings.noLongerAvailable), style = Theme.captionFont, color = Theme.muted)
        }
    }
}
