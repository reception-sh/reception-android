package sh.reception.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import sh.reception.R
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.background
import sh.reception.foreground
import sh.reception.imageBorder
import sh.reception.strings.Strings
import sh.reception.viewmodel.ComposerModel

/** The picked photos above the field, each with a remove button. */
@Composable
internal fun ComposerPhotos(model: ComposerModel, sizes: ComposerDimensions) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(sizes.small),
        horizontalArrangement = Arrangement.spacedBy(sizes.small),
    ) {
        val shape = RoundedCornerShape(sizes.small)
        for (photo in model.photos) {
            Box(Modifier.size(sizes.chip)) {
                val bitmap = remember(photo.id) { photo.preview.asImageBitmap() }
                Image(bitmap, ReceptionLocalization.string(Strings.photo), contentScale = ContentScale.Crop,
                    modifier = Modifier.size(sizes.chip).clip(shape).border(Theme.imageBorderWidth, Theme.imageBorder, shape))
                val label = ReceptionLocalization.string(Strings.removePhoto)
                Icon(painterResource(R.drawable.reception_symbol_cancel), contentDescription = null, tint = Theme.foreground,
                    modifier = Modifier.align(Alignment.TopEnd).background(Theme.background, CircleShape)
                        .clickable(role = Role.Button) { model.remove(photo.id) }.semantics { contentDescription = label })
            }
        }
    }
}

/**
 * Opens the system photo picker for as many photos as the composer still takes, through the hosting activity's
 * result registry. Null when the host provides none (an embedded chat outside a `ComponentActivity`).
 */
@Composable
internal fun rememberPhotoPicker(model: ComposerModel): (() -> Unit)? {
    if (LocalActivityResultRegistryOwner.current == null) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remaining = model.remainingPhotos
    val several = remember(remaining) { PickMultipleVisualMedia(maxOf(2, remaining)) }
    val many = rememberLauncherForActivityResult(several) { uris -> scope.launch { model.load(context, uris) } }
    val one = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { model.load(context, listOf(uri)) }
    }
    val request = PickVisualMediaRequest(PickVisualMedia.ImageOnly)
    return {
        if (remaining > 1) many.launch(request) else if (remaining == 1) one.launch(request)
    }
}
