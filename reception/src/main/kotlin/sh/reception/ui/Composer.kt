package sh.reception.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import sh.reception.R
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.accent
import sh.reception.bodyFont
import sh.reception.captionFont
import sh.reception.composerDisabled
import sh.reception.error
import sh.reception.foreground
import sh.reception.muted
import sh.reception.outgoingForeground
import sh.reception.strings.Strings
import sh.reception.viewmodel.ChatModel
import sh.reception.viewmodel.MAXIMUM_TEXT_LENGTH
import sh.reception.viewmodel.cooldownAllowsSend
import sh.reception.viewmodel.photosPaused

/**
 * The photo button and the message field with its send button, in the iOS 18 Messages metrics; in its place a
 * notice while this device may not send. [onFocus] runs when the person puts the cursor in the field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Composer(chat: ChatModel, onFocus: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = with(LocalDensity.current) { Theme.bodySize.toDp().value / Theme.bodySize.value }
        val sizes = ComposerDimensions.resolve(maxWidth, WindowInsets.isImeVisible, scale)
        Box(Modifier.fillMaxWidth().padding(start = sizes.leading, end = sizes.trailing, bottom = sizes.bottom)) {
            when {
                chat.sendingDisabled -> SendingDisabledNotice(
                    ReceptionLocalization.string(Strings.messagesCantBeSentFromThisDevice), sizes.height)
                chat.verificationRequired -> SendingDisabledNotice(
                    ReceptionLocalization.string(Strings.signInToSendMessages), sizes.height)
                else -> Controls(chat, sizes, onFocus)
            }
        }
    }
}

@Composable
private fun Controls(chat: ChatModel, sizes: ComposerDimensions, onFocus: () -> Unit) {
    val model = chat.composer
    var multiline by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(sizes.small)) {
        if (model.photos.isNotEmpty()) ComposerPhotos(model, sizes)
        chat.uploadProgress?.let { progress ->
            val label = ReceptionLocalization.string(Strings.uploadingPhotos)
            LinearProgressIndicator(progress = { progress.toFloat() }, color = Theme.accent,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.uploadProgressHorizontalInset)
                    .semantics { contentDescription = label })
        }
        model.error?.let { Text(it, style = Theme.captionFont, color = Theme.error) }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            PhotoButton(chat, sizes, multiline)
            Field(chat, sizes, multiline, onMultilineChange = { multiline = it }, onFocus = onFocus,
                modifier = Modifier.weight(1f))
        }
        if (model.loading) {
            val label = ReceptionLocalization.string(Strings.loadingPhotos)
            CircularProgressIndicator(color = Theme.muted, modifier = Modifier.align(Alignment.CenterHorizontally)
                .semantics { contentDescription = label })
        }
        if (model.text.length > MAXIMUM_TEXT_LENGTH) {
            Text(ReceptionLocalization.string(Strings.messagesCanContainUpTo4000), style = Theme.captionFont,
                color = Theme.error)
        }
    }
}

@Composable
private fun PhotoButton(chat: ChatModel, sizes: ComposerDimensions, multiline: Boolean) {
    val model = chat.composer
    val pick = rememberPhotoPicker(model)
    val enabled = pick != null && model.remainingPhotos > 0 && !model.loading && !chat.photosPaused
    val label = ReceptionLocalization.string(Strings.addPhotos)
    Box(
        modifier = Modifier.offset(y = -(sizes.vertical + sizes.buttonBottom(sizes.photo, multiline))).size(sizes.photo)
            .composerButtonSurface().clickable(enabled = enabled, role = Role.Button) { pick?.invoke() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.reception_symbol_add_photo_alternate), contentDescription = null,
            tint = if (chat.photosPaused || !enabled) Theme.muted else Theme.foreground, modifier = Modifier.size(sizes.photoIcon))
    }
}

@Composable
private fun Field(
    chat: ChatModel,
    sizes: ComposerDimensions,
    multiline: Boolean,
    onMultilineChange: (Boolean) -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier,
) {
    val model = chat.composer
    val hasContent = model.text.isNotEmpty() || model.photos.isNotEmpty()
    val sendable = model.canSend && chat.canSend && chat.cooldownAllowsSend(photos = model.photos.isNotEmpty())
    Row(
        modifier = modifier.defaultMinSize(minHeight = sizes.height).composerSurface(multiline, sizes.radius)
            .padding(start = sizes.textLeading, end = sizes.sendInset, top = sizes.vertical, bottom = sizes.vertical),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(sizes.small),
    ) {
        BasicTextField(
            value = model.text,
            onValueChange = { model.text = it },
            textStyle = Theme.bodyFont.copy(color = Theme.foreground),
            cursorBrush = SolidColor(Theme.accent),
            maxLines = Theme.COMPOSER_LINE_LIMIT,
            onTextLayout = { onMultilineChange(it.lineCount > 1) },
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = sizes.innerHeight)
                .onFocusChanged { if (it.isFocused) onFocus() },
            decorationBox = { inner ->
                Box(Modifier.defaultMinSize(minHeight = sizes.innerHeight), contentAlignment = Alignment.CenterStart) {
                    if (model.text.isEmpty()) {
                        Text(ReceptionLocalization.string(Strings.message), style = Theme.bodyFont, color = Theme.muted, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        AnimatedVisibility(hasContent, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            val label = ReceptionLocalization.string(Strings.sendMessage)
            Box(
                modifier = Modifier.offset(y = -sizes.buttonBottom(sizes.sendHeight, multiline))
                    .size(sizes.sendWidth, sizes.sendHeight)
                    .background(if (sendable) Theme.accent else Theme.composerDisabled, CircleShape)
                    .clickable(enabled = sendable, role = Role.Button) { model.send(chat) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.reception_symbol_arrow_upward), contentDescription = null,
                    tint = if (sendable) Theme.outgoingForeground else Theme.muted, modifier = Modifier.size(sizes.sendIcon))
            }
        }
    }
}
