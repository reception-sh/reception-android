package sh.reception.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import sh.reception.R
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.accent
import sh.reception.configuration.PushPrompt
import sh.reception.footnoteFont
import sh.reception.muted
import sh.reception.strings.Strings
import sh.reception.viewmodel.PushPromptSlot

/** The notification card or status line under the person's latest message. */
@Composable
internal fun PushPromptSlotView(slot: PushPromptSlot, decline: () -> Unit, accept: () -> Unit, modifier: Modifier = Modifier) {
    when (slot) {
        is PushPromptSlot.Card -> Card(slot.prompt, decline, accept, modifier)
        PushPromptSlot.Status -> Belled(ReceptionLocalization.string(Strings.youllGetANotificationWhenWeReply),
            modifier.fillMaxWidth())
        PushPromptSlot.None -> Unit
    }
}

@Composable
private fun Card(prompt: PushPrompt, decline: () -> Unit, accept: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Theme.pushPromptVertical),
        verticalArrangement = Arrangement.spacedBy(Theme.pushPromptSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Belled(ReceptionLocalization.string(text(prompt)), Modifier.fillMaxWidth())
        // Equal buttons, so the choice is not nudged before the system asks.
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.pushPromptButtonSpacing)) {
            TextButton(ReceptionLocalization.string(Strings.noThanks), decline)
            TextButton(ReceptionLocalization.string(Strings.continueText), accept)
        }
    }
}

/** The bell sits inside the text, so a sentence that wraps stays centered line by line. */
@Composable
private fun Belled(text: String, modifier: Modifier) {
    val bell = "bell"
    val content = mapOf(bell to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
        Icon(painterResource(R.drawable.reception_symbol_notifications), contentDescription = null, tint = Theme.muted)
    })
    Text(
        buildAnnotatedString {
            appendInlineContent(bell, "")
            append(" ")
            append(text)
        },
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
        inlineContent = content,
        style = Theme.footnoteFont,
        color = Theme.muted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TextButton(label: String, action: () -> Unit) {
    Box(
        Modifier.heightIn(min = Theme.pushPromptButtonHeight).clickable(role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Theme.footnoteFont.copy(fontWeight = FontWeight.SemiBold), color = Theme.accent)
    }
}

private fun text(prompt: PushPrompt): String = when (prompt) {
    PushPrompt.REPLIES -> Strings.getANotificationWhenWeReply
    PushPrompt.OFFERS -> Strings.getNotificationsForRepliesOffersAnd
}
