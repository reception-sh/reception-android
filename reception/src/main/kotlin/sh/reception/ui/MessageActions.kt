package sh.reception.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import sh.reception.model.Attachment
import sh.reception.model.Message
import sh.reception.viewmodel.ChatModel
import sh.reception.viewmodel.RetryAvailability
import sh.reception.viewmodel.cancelAutomaticRetry
import sh.reception.viewmodel.canOpenPaywall
import sh.reception.viewmodel.isHostLink
import sh.reception.viewmodel.linkCardUrl
import sh.reception.viewmodel.openLink
import sh.reception.viewmodel.openPaywall
import sh.reception.viewmodel.openReview
import sh.reception.viewmodel.openTextLink
import sh.reception.viewmodel.retry
import sh.reception.viewmodel.retryAvailability

/** What a message row can do, bound to its message by the history. */
internal class MessageActions(
    val retryAvailability: RetryAvailability,
    val retry: () -> Unit,
    val cancelRetry: () -> Unit,
    val openReview: () -> Unit,
    /** The link card leads back into the host app. */
    val linkOpensInApp: Boolean,
    val openLink: () -> Unit,
    /** Web addresses in the text. */
    val openTextLink: (String) -> Unit,
    val canOpenPaywall: Boolean,
    val openPaywall: () -> Unit,
    val openPhoto: (Attachment) -> Unit,
)

/** A reply that lands while the chat is open taps the person lightly; the first load stays quiet. */
@Composable
internal fun ReplyFeedback(model: ChatModel) {
    val view = LocalView.current
    val reply = model.messages.lastOrNull { it.sender == "AGENT" }?.id
    var previous by remember { mutableStateOf(reply) }
    LaunchedEffect(reply) {
        if (previous != null && reply != null && reply != previous && model.isVisible) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        previous = reply
    }
}

/** The row's actions, bound to [message]. */
internal fun ChatModel.actions(message: Message, openPhoto: (Attachment) -> Unit): MessageActions {
    val link = message.linkCardUrl
    return MessageActions(
        retryAvailability = retryAvailability(message),
        retry = { retry(message.id) },
        cancelRetry = { cancelAutomaticRetry(message.id) },
        openReview = { message.reviewUrl?.let { openReview(it, message.id) } },
        linkOpensInApp = link != null && isHostLink(link),
        openLink = { link?.let { openLink(it, message.id) } },
        openTextLink = { openTextLink(it) },
        canOpenPaywall = canOpenPaywall(message),
        openPaywall = { openPaywall(message) },
        openPhoto = openPhoto,
    )
}
