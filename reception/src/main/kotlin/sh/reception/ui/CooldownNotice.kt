package sh.reception.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import sh.reception.R
import sh.reception.Theme
import sh.reception.client.ServerCooldowns
import sh.reception.muted
import sh.reception.textStyle
import sh.reception.viewmodel.CooldownText

/** Why sending waits, counting down on whole seconds. */
@Composable
internal fun CooldownNotice(cooldown: ServerCooldowns.Cooldown, modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis()) { Theme.countdownTicks().collect { value = it } }
    val style = Theme.textStyle(Theme.connectionSize)
    val iconSize = with(LocalDensity.current) { Theme.connectionSize.toDp() }
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Theme.small, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.reception_symbol_schedule), contentDescription = null, tint = Theme.muted,
            modifier = Modifier.size(iconSize))
        Text(CooldownText.notice(cooldown, now), style = style, color = Theme.muted, textAlign = TextAlign.Center)
    }
}
