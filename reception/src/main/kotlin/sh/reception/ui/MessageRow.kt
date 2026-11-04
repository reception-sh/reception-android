package sh.reception.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.model.Message
import sh.reception.muted
import sh.reception.timestampFont
import sh.reception.viewmodel.MessageLayout
import sh.reception.viewmodel.RetryAvailability
import java.text.SimpleDateFormat
import java.util.Date

/** One message with its timestamp and author name; agent replies keep the team photo column when it is shown. */
@Composable
internal fun MessageRow(
    message: Message,
    layout: MessageLayout,
    width: Dp,
    retryAvailability: RetryAvailability,
    retry: () -> Unit,
    cancelRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = layout.spacing), verticalArrangement = Arrangement.spacedBy(Theme.small)) {
        if (layout.showsTimestamp) {
            Text(timestamp(message.createdAt), style = Theme.timestampFont, color = Theme.muted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Column(verticalArrangement = Arrangement.spacedBy(Theme.authorNameSpacing)) {
            val avatar = with(LocalDensity.current) { Theme.avatarSize * fontScale }
            val author = layout.author
            if (layout.showsAuthorName && author != null) {
                val column = if (layout.reservesPhotoColumn) avatar + Theme.small else Theme.zero
                Text(author.name, style = Theme.timestampFont, color = Theme.muted,
                    modifier = Modifier.padding(start = column + Theme.bubbleHorizontal))
            }
            if (layout.reservesPhotoColumn) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Theme.small)) {
                    Spacer(Modifier.width(avatar))
                    MessageBubble(message, width - avatar - Theme.small, layout, retryAvailability, retry, cancelRetry)
                }
            } else {
                MessageBubble(message, width, layout, retryAvailability, retry, cancelRetry)
            }
        }
    }
}

/** "Tue 14:05" or "Tue 2:05 PM", in the chat language and the device's 12/24-hour setting. */
@Composable
private fun timestamp(time: Long): String {
    val context = LocalContext.current
    val locale = ReceptionLocalization.locale
    val skeleton = if (DateFormat.is24HourFormat(context)) "EEEHHmm" else "EEEhmma"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(time))
}
