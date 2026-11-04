package sh.reception.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.appearance
import sh.reception.configuration.AppearanceIcon
import sh.reception.configuration.AppearanceIconView
import sh.reception.dismissFill
import sh.reception.foreground
import sh.reception.strings.Strings

/** The optional close button: a remote or app image, else the appearance's close symbol, in a filled circle. */
@Composable
internal fun DismissButton(action: () -> Unit, modifier: Modifier = Modifier) {
    val appearance = Theme.appearance
    val label = ReceptionLocalization.string(Strings.close)
    Box(
        modifier = modifier.size(Theme.dismissDiameter).clip(CircleShape).background(Theme.dismissFill)
            .clickable(role = Role.Button, onClick = action).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val remote = appearance.remoteCloseImage
        val local = appearance.closeImage
        val symbol = appearance.closeIcon.symbol
        when {
            remote != null -> AppearanceIconView(AppearanceIcon.Remote(remote), Theme.dismissImageSize, Theme.foreground, symbol)
            local != null -> AppearanceIconView(AppearanceIcon.Local(local), Theme.dismissImageSize, Theme.foreground, symbol)
            else -> AppearanceIconView(AppearanceIcon.Symbol(symbol), Theme.dismissImageSize, Theme.foreground, symbol)
        }
    }
}
