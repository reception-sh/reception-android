package sh.reception.ui

import androidx.compose.runtime.Composable
import sh.reception.Theme
import sh.reception.appearance
import sh.reception.model.Message
import sh.reception.viewmodel.cardLabel

/** Asks for a rating: opens the app's Play Store listing. */
@Composable
internal fun ReviewCard(message: Message, openReview: () -> Unit) {
    ActionCard(message.text, message.cardLabel, symbol = "star.fill", accessory = "arrow.up.right",
        style = Theme.appearance.reviewCard, action = openReview)
}
