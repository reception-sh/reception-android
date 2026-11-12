package sh.reception.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.imageBorder
import sh.reception.imagePlaceholder
import sh.reception.model.Attachment
import sh.reception.strings.Strings
import sh.reception.viewmodel.MessagePhotos

/**
 * A photo in a message: a confirmed attachment ([RemoteAttachmentImage], which [openPhoto] opens), or the local JPEG of
 * a message the service has not confirmed yet ([onClick] retries a failed one). Sized from the photo's aspect ratio, at
 * most [maximumWidth] wide and the theme's maximum height tall.
 */
@Composable
internal fun MessageImage(
    attachment: Attachment? = null,
    data: ByteArray? = null,
    maximumWidth: Dp,
    openPhoto: (() -> Unit)? = null,
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
            attachment != null -> RemoteAttachmentImage(attachment, fills = true, openPhoto = openPhoto)
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
