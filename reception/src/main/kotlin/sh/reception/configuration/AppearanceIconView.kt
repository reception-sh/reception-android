package sh.reception.configuration

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import sh.reception.Theme

/**
 * Draws [icon] in a [size] square: symbols in [tint] at [Theme.symbolScale], images in their own colors, fitted and clipped at their actual
 * bounds with [imageCornerRadius] (capped at half the shorter edge). A symbol the SDK cannot draw shows [builtIn].
 */
@Composable
internal fun AppearanceIconView(
    icon: AppearanceIcon,
    size: Dp,
    tint: Color,
    builtIn: String,
    modifier: Modifier = Modifier,
    imageCornerRadius: Dp = 0.dp,
) {
    when (icon) {
        is AppearanceIcon.Symbol -> {
            val drawable = AppearanceSymbols.drawable(icon.name) ?: AppearanceSymbols.drawable(builtIn) ?: return
            Icon(painterResource(drawable), contentDescription = null, tint = tint,
                modifier = modifier.size(size).scale(Theme.symbolScale))
        }
        is AppearanceIcon.Remote -> {
            val context = LocalContext.current
            val request = ImageRequest.Builder(context).data(icon.url).build()
            val painter = rememberAsyncImagePainter(request, AppearanceImages.loader(context))
            FittedImage(painter, size, imageCornerRadius, modifier)
        }
        is AppearanceIcon.Local -> FittedImage(icon.painter, size, imageCornerRadius, modifier)
        is AppearanceIcon.LocalResource -> FittedImage(painterResource(icon.id), size, imageCornerRadius, modifier)
        AppearanceIcon.Hidden -> Unit
    }
}

@Composable
private fun FittedImage(painter: Painter, size: Dp, cornerRadius: Dp, modifier: Modifier) {
    val intrinsic = painter.intrinsicSize
    val ratio = if (intrinsic.isSpecified && intrinsic.width > 0 && intrinsic.height > 0) intrinsic.width / intrinsic.height else 1f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1).clip(RoundedCornerShape(cornerRadius)),
        )
    }
}
