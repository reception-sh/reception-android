package sh.reception.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import sh.reception.R
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.error
import sh.reception.muted
import sh.reception.strings.Strings
import sh.reception.success
import sh.reception.textStyle
import sh.reception.viewmodel.ConnectionState

/** The connection state above the composer; hidden while all is well. */
@Composable
internal fun ConnectionBanner(state: ConnectionState, modifier: Modifier = Modifier) {
    // A banner fading out keeps showing the state it had.
    var shown by remember { mutableStateOf(state) }
    if (state != ConnectionState.HIDDEN) shown = state
    AnimatedVisibility(state != ConnectionState.HIDDEN, modifier, enter = fadeIn(), exit = fadeOut()) {
        val title = ReceptionLocalization.string(when (shown) {
            ConnectionState.HIDDEN, ConnectionState.OFFLINE -> Strings.noConnection
            ConnectionState.CONNECTING -> Strings.connecting
            ConnectionState.CONNECTED -> Strings.connected
            ConnectionState.PAUSED -> Strings.liveUpdatesPaused
        })
        val icon = when (shown) {
            ConnectionState.OFFLINE -> R.drawable.reception_symbol_wifi_off
            ConnectionState.PAUSED -> R.drawable.reception_symbol_pause_circle
            else -> R.drawable.reception_symbol_wifi
        }
        val color = when (shown) {
            ConnectionState.CONNECTING, ConnectionState.PAUSED -> Theme.muted
            ConnectionState.CONNECTED -> Theme.success
            else -> Theme.error
        }
        val label = if (shown == ConnectionState.CONNECTING) ReceptionLocalization.string(Strings.connectingFd3e79) else title
        val style = Theme.textStyle(Theme.connectionSize, FontWeight.Medium)
        val iconSize = with(LocalDensity.current) { Theme.connectionSize.toDp() }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Theme.spacing).clearAndSetSemantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
            horizontalArrangement = Arrangement.spacedBy(Theme.small, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A long title wraps before the dots, which keep their room.
                Text(title, style = style, color = color, modifier = Modifier.weight(1f, fill = false))
                if (shown == ConnectionState.CONNECTING) ConnectionDots(Modifier.padding(start = Theme.connectionDotTextSpacing))
            }
        }
    }
}
