package sh.reception.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.reception.R
import sh.reception.ReceptionLocalization
import sh.reception.ReceptionState
import sh.reception.Theme
import sh.reception.bodyFont
import sh.reception.client.AttachmentImageState
import sh.reception.imageBorder
import sh.reception.imagePlaceholder
import sh.reception.model.Attachment
import sh.reception.muted
import sh.reception.strings.Strings
import sh.reception.viewmodel.MessagePhotos

/**
 * A photo in a message: a confirmed attachment through the session's shared loader (with Retry after a failure), or
 * the local JPEG of a message the service has not confirmed yet. Sized from the photo's aspect ratio, at most
 * [maximumWidth] wide and the theme's maximum height tall.
 */
@Composable
internal fun MessageImage(
    attachment: Attachment? = null,
    data: ByteArray? = null,
    maximumWidth: Dp,
    onClick: (() -> Unit)? = null,
) {
    val local by produceState<ImageBitmap?>(null, data) {
        value = data?.let { withContext(Dispatchers.Default) { MessagePhotos.preview(it)?.asImageBitmap() } }
    }
    val shape = RoundedCornerShape(Theme.bubbleRadius)
    val photo = ReceptionLocalization.string(Strings.photo)
    Box(
        modifier = Modifier.size(size(attachment, local, maximumWidth)).clip(shape)
            .border(Theme.imageBorderWidth, Theme.imageBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        val bitmap = local
        when {
            attachment != null -> RemoteImage(attachment)
            bitmap != null -> Image(bitmap, photo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Box(Modifier.fillMaxSize().background(Theme.imagePlaceholder))
        }
    }
}

private fun size(attachment: Attachment?, local: ImageBitmap?, maximumWidth: Dp): DpSize {
    val width = attachment?.width
    val height = attachment?.height
    val ratio = when {
        width != null && height != null && width > 0 && height > 0 -> width.toFloat() / height
        local != null && local.width > 0 && local.height > 0 -> local.width.toFloat() / local.height
        else -> 1f
    }
    val fitted = minOf(maximumWidth, Theme.imageMaximumHeight * ratio)
    return DpSize(fitted, fitted / ratio)
}

@Composable
private fun RemoteImage(attachment: Attachment) {
    val session = ReceptionState.session
    val loader = remember(attachment.id, session) { session?.let { it.attachmentImages.loader(attachment, it) } }
    if (loader == null) return Box(Modifier.fillMaxSize().background(Theme.imagePlaceholder))
    DisposableEffect(loader) {
        loader.appear()
        onDispose { loader.disappear() }
    }
    val state by loader.state.collectAsState()
    when (val current = state) {
        is AttachmentImageState.Loaded -> Image(current.bitmap.asImageBitmap(), ReceptionLocalization.string(Strings.photo),
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        AttachmentImageState.Failed -> {
            val retry = ReceptionLocalization.string(Strings.retry)
            Column(
                modifier = Modifier.fillMaxSize().background(Theme.imagePlaceholder).clickable(onClick = loader::retry)
                    .semantics { contentDescription = retry },
                verticalArrangement = Arrangement.spacedBy(Theme.small, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.reception_symbol_error), contentDescription = null, tint = Theme.muted)
                Text(retry, style = Theme.bodyFont, color = Theme.muted)
            }
        }
        AttachmentImageState.Loading, AttachmentImageState.Idle -> Box(
            Modifier.fillMaxSize().background(Theme.imagePlaceholder), contentAlignment = Alignment.Center,
        ) {
            if (current == AttachmentImageState.Loading) CircularProgressIndicator(color = Theme.muted)
        }
    }
}
