package sh.reception.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import sh.reception.R
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.accent
import sh.reception.bodyFont
import sh.reception.error
import sh.reception.incomingForeground
import sh.reception.model.Message
import sh.reception.model.MessageStatus
import sh.reception.outgoingBackground
import sh.reception.outgoingForeground
import sh.reception.secondary
import sh.reception.strings.Strings
import sh.reception.viewmodel.MessageLayout
import sh.reception.viewmodel.RetryAvailability

/**
 * A message's bubbles (text, then photos), its failure icon and its status line. Outgoing bubbles sit at the end in
 * the accent color, incoming ones at the start; a failed message that may be retried now retries on tap.
 */
@Composable
internal fun MessageBubble(
    message: Message,
    width: Dp,
    layout: MessageLayout,
    retryAvailability: RetryAvailability,
    retry: () -> Unit,
    cancelRetry: () -> Unit,
) {
    val outgoing = message.sender == "USER"
    val canRetry = retryAvailability == RetryAvailability.Now
    val failed = message.status == MessageStatus.FAILED
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier.widthIn(max = width - Theme.control),
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(Theme.deliveryStatusSpacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.small)) {
                if (failed) FailureIcon(if (canRetry) retry else null)
                Bubbles(message, width, layout.olderOutgoingCount, failed && canRetry, retry)
            }
            DeliveryStatus(message, layout.showsDeliveryStatus, retryAvailability, retry, cancelRetry)
        }
    }
}

@Composable
private fun FailureIcon(retry: (() -> Unit)?) {
    val size = with(LocalDensity.current) { Theme.failureIconSize * fontScale }
    val label = ReceptionLocalization.string(Strings.retry)
    // A cooldown holds the message without Retry; the status line below states it.
    val action = if (retry == null) Modifier else Modifier.clickable(role = Role.Button, onClick = retry)
        .semantics { contentDescription = label }
    Icon(painterResource(R.drawable.reception_symbol_error_filled), contentDescription = null, tint = Theme.error,
        modifier = action.size(size))
}

@Composable
private fun Bubbles(message: Message, width: Dp, olderBy: Int, retriesOnTap: Boolean, retry: () -> Unit) {
    val outgoing = message.sender == "USER"
    val pending = outgoing && message.status == MessageStatus.SENDING && message.showsPending
    Column(
        modifier = Modifier.alpha(if (pending) Theme.SENDING_OPACITY else Theme.FULL_OPACITY),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Theme.groupedSpacing),
    ) {
        if (message.text.isNotEmpty()) TextBubble(message, olderBy, retriesOnTap, retry)
        val imageWidth = width * Theme.IMAGE_WIDTH_FRACTION
        val tap = if (retriesOnTap) retry else null
        for (attachment in message.attachments) {
            // The lightbox opens confirmed photos with the next phase; a failed one retries.
            MessageImage(attachment = attachment, maximumWidth = imageWidth, onClick = if (message.status == MessageStatus.FAILED) tap else null)
        }
        if (message.attachments.isEmpty()) {
            for (data in message.images) MessageImage(data = data, maximumWidth = imageWidth, onClick = tap)
        }
    }
}

@Composable
private fun TextBubble(message: Message, olderBy: Int, retriesOnTap: Boolean, retry: () -> Unit) {
    val outgoing = message.sender == "USER"
    val color = if (outgoing) Theme.outgoingForeground else Theme.incomingForeground
    val fill = if (outgoing) Theme.outgoingBackground(olderBy) else Theme.secondary
    val context = LocalContext.current
    val toolbar = Theme.accent.toArgb()
    val text = LinkedText.annotated(message.text, color) { TextLinks.open(context, it, toolbar) }
    val bubble = Modifier.background(fill, BubbleShape).padding(horizontal = Theme.bubbleHorizontal, vertical = Theme.bubbleVertical)
    if (retriesOnTap) {
        Text(text, style = Theme.bodyFont, color = color, modifier = Modifier.clip(BubbleShape).clickable(onClick = retry).then(bubble))
    } else {
        // Long press selects, with Copy in the selection toolbar, like the iOS context menu.
        SelectionContainer { Text(text, style = Theme.bodyFont, color = color, modifier = bubble) }
    }
}
