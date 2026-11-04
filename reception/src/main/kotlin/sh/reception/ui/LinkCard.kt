package sh.reception.ui

import androidx.compose.runtime.Composable
import sh.reception.Theme
import sh.reception.appearance
import sh.reception.model.Message
import sh.reception.viewmodel.cardLabel

/** Opens a link; the accessory says whether it leads into the app or out of it. */
@Composable
internal fun LinkCard(message: Message, isInApp: Boolean, openLink: () -> Unit) {
    ActionCard(message.text, message.cardLabel, symbol = "link",
        accessory = if (isInApp) "chevron.right" else "arrow.up.right", style = Theme.appearance.linkCard, action = openLink)
}
