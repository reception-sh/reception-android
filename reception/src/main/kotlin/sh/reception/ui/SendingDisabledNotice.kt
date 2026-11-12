package sh.reception.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import sh.reception.Theme
import sh.reception.muted
import sh.reception.textStyle

/** Takes the composer's place while this device may not send, at the composer's height. */
@Composable
internal fun SendingDisabledNotice(text: String, height: Dp) {
    Box(Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = Theme.spacing), contentAlignment = Alignment.Center) {
        Text(text, style = Theme.textStyle(Theme.sendingDisabledSize), color = Theme.muted, textAlign = TextAlign.Center,
            maxLines = Theme.SENDING_DISABLED_LINE_LIMIT)
    }
}
