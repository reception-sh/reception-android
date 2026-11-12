package sh.reception.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.accent
import sh.reception.error
import sh.reception.model.Message
import sh.reception.model.MessageStatus
import sh.reception.muted
import sh.reception.strings.Strings
import sh.reception.textStyle
import sh.reception.viewmodel.CooldownText
import sh.reception.viewmodel.RetryAvailability

/** "Sent" or the pending "Sending…" under the latest outgoing message, or "Not delivered" with its actions. */
@Composable
internal fun DeliveryStatus(
    message: Message,
    showsDeliveryStatus: Boolean,
    retryAvailability: RetryAvailability,
    retry: () -> Unit,
    cancelRetry: () -> Unit,
) {
    val style = Theme.textStyle(Theme.deliveryStatusSize)
    if (message.status == MessageStatus.FAILED) {
        FittingRow(rowSpacing = Theme.small, columnSpacing = Theme.deliveryStatusSpacing) {
            Text(ReceptionLocalization.string(Strings.notDelivered), style = style, color = Theme.error)
            if (retryAvailability is RetryAvailability.Automatic) {
                Countdown(retryAvailability.until, style)
                val label = ReceptionLocalization.string(Strings.cancelAutomaticSending)
                TextAction(ReceptionLocalization.string(Strings.cancel), style, enabled = true, cancelRetry,
                    Modifier.semantics { contentDescription = label })
            } else {
                TextAction(ReceptionLocalization.string(Strings.retry), style,
                    enabled = retryAvailability == RetryAvailability.Now, retry)
            }
        }
    } else if (showsDeliveryStatus && message.status == MessageStatus.SENT) {
        Text(ReceptionLocalization.string(Strings.sent), style = style, color = Theme.muted)
    } else if (showsDeliveryStatus && message.showsPending) {
        SendingStatus(style)
    }
}

@Composable
private fun Countdown(until: Long, style: TextStyle) {
    val now by produceState(System.currentTimeMillis()) { Theme.countdownTicks().collect { value = it } }
    Text(CooldownText.resend(until, now), style = style, color = Theme.muted)
}

@Composable
private fun TextAction(text: String, style: TextStyle, enabled: Boolean, action: () -> Unit, modifier: Modifier = Modifier) {
    Text(text, style = style, color = if (enabled) Theme.accent else Theme.muted,
        modifier = modifier.clickable(enabled = enabled, role = Role.Button, onClick = action))
}

/** "Sending" with three dots that pulse one after another; still under "Remove animations". */
@Composable
private fun SendingStatus(style: TextStyle) {
    val still = reducesMotion()
    val elapsed by produceState(0.0, still) {
        if (still) return@produceState
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1e9 }
    }
    val label = ReceptionLocalization.string(Strings.sendingCf7655)
    Row(Modifier.clearAndSetSemantics { contentDescription = label }) {
        Text(ReceptionLocalization.string(Strings.sending), style = style, color = Theme.muted)
        repeat(Theme.CONNECTION_DOT_COUNT) { index ->
            val opacity = if (still) Theme.FULL_OPACITY
            else Theme.SENDING_DOT_RESTING_OPACITY + Theme.SENDING_DOT_OPACITY_RANGE * Theme.typingPulse(elapsed, index).toFloat()
            Text(".", style = style, color = Theme.muted, modifier = Modifier.alpha(opacity))
        }
    }
}
