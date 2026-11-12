package sh.reception.ui

import androidx.compose.runtime.Composable
import sh.reception.Theme
import sh.reception.appearance
import sh.reception.model.Message
import sh.reception.viewmodel.cardLabel

/** Offers one of the app's paywalls; unavailable while the app cannot present it. */
@Composable
internal fun PaywallCard(message: Message, isAvailable: Boolean, openPaywall: () -> Unit) {
    ActionCard(message.text, message.cardLabel, symbol = "gift.fill", accessory = "chevron.right",
        style = Theme.appearance.offerCard, isAvailable = isAvailable, action = openPaywall)
}
