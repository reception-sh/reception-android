package sh.reception.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.captionFont
import sh.reception.model.TeamMember
import sh.reception.muted
import sh.reception.secondary

/** Shown only for a known author: their photo, or their initial until the photo loads. */
@Composable
internal fun TeamAvatar(member: TeamMember?, image: Bitmap?) {
    val size = Theme.avatarSize * LocalDensity.current.fontScale
    Box(Modifier.size(size).clip(CircleShape).background(Theme.secondary).clearAndSetSemantics {},
        contentAlignment = Alignment.Center) {
        if (image != null) {
            val bitmap = remember(image) { image.asImageBitmap() }
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            val initial = member?.name?.take(1)?.uppercase(ReceptionLocalization.locale) ?: ""
            Text(initial, style = Theme.captionFont.copy(fontWeight = FontWeight.SemiBold), color = Theme.muted)
        }
    }
}
