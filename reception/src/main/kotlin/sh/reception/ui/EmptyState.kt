package sh.reception.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import sh.reception.Theme
import sh.reception.appearance
import sh.reception.configuration.AppearanceIcon
import sh.reception.configuration.AppearanceIconView
import sh.reception.foreground
import sh.reception.muted
import sh.reception.textStyle
import sh.reception.welcomeIconColor

private const val BUILT_IN_ICON = "bubble.left.and.bubble.right"

/** The welcome state of a chat without messages: icon, title and text, each of which the appearance may hide. */
@Composable
internal fun EmptyState() {
    val appearance = Theme.appearance
    val icon = appearance.welcomeIcon ?: AppearanceIcon.Symbol(BUILT_IN_ICON)
    val scale = LocalDensity.current.fontScale
    Column(
        modifier = Modifier.fillMaxSize().padding(start = Theme.spacing, end = Theme.spacing, bottom = Theme.emptyBottomInset),
        verticalArrangement = Arrangement.spacedBy(Theme.small, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != AppearanceIcon.Hidden) {
            AppearanceIconView(
                icon = icon,
                size = (appearance.welcomeIconSize ?: Theme.emptyIconSize) * scale,
                tint = Theme.welcomeIconColor,
                builtIn = BUILT_IN_ICON,
                imageCornerRadius = appearance.welcomeIconCornerRadius * scale,
                modifier = Modifier.offset(appearance.welcomeIconOffset.x, appearance.welcomeIconOffset.y)
                    .padding(bottom = Theme.small).clearAndSetSemantics {},
            )
        }
        if (!appearance.hidesWelcomeTitle) {
            Text(appearance.welcomeTitle, style = Theme.textStyle(Theme.emptyTitleSize, FontWeight.SemiBold),
                color = Theme.foreground, textAlign = TextAlign.Center)
        }
        if (!appearance.hidesWelcomeText) {
            Text(appearance.welcomeText, style = Theme.textStyle(Theme.emptySubtitleSize), color = Theme.muted,
                textAlign = TextAlign.Center)
        }
    }
}
