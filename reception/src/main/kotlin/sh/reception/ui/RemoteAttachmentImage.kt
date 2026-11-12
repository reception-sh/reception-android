package sh.reception.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import sh.reception.R
import sh.reception.ReceptionLocalization
import sh.reception.ReceptionState
import sh.reception.Theme
import sh.reception.bodyFont
import sh.reception.client.AttachmentImageState
import sh.reception.imagePlaceholder
import sh.reception.model.Attachment
import sh.reception.muted
import sh.reception.strings.Strings

/**
 * A confirmed photo through the session's shared loader, so the bubble and the lightbox load it once: a placeholder
 * while it loads, Retry after a failure. [fills] crops it to its frame (the bubble); otherwise it fits (the lightbox).
 */
@Composable
internal fun RemoteAttachmentImage(attachment: Attachment, fills: Boolean, modifier: Modifier = Modifier, openPhoto: (() -> Unit)? = null) {
    val session = ReceptionState.session
    val loader = remember(attachment.id, session) { session?.let { it.attachmentImages.loader(attachment, it) } }
    if (loader == null) return Box(modifier.fillMaxSize().background(Theme.imagePlaceholder))
    DisposableEffect(loader) {
        loader.appear()
        onDispose { loader.disappear() }
    }
    val state by loader.state.collectAsState()
    when (val current = state) {
        is AttachmentImageState.Loaded -> {
            val label = ReceptionLocalization.string(if (openPhoto != null) Strings.openPhoto else Strings.photo)
            val tap = if (openPhoto != null) Modifier.clickable(role = Role.Button, onClick = openPhoto) else Modifier
            Image(current.bitmap.asImageBitmap(), contentDescription = null,
                contentScale = if (fills) ContentScale.Crop else ContentScale.Fit,
                modifier = modifier.fillMaxSize().then(tap).semantics { contentDescription = label })
        }
        AttachmentImageState.Failed -> {
            val retry = ReceptionLocalization.string(Strings.retry)
            Column(
                modifier = modifier.fillMaxSize().background(Theme.imagePlaceholder)
                    .clickable(role = Role.Button, onClick = loader::retry).semantics { contentDescription = retry },
                verticalArrangement = Arrangement.spacedBy(Theme.small, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.reception_symbol_error), contentDescription = null, tint = Theme.muted)
                Text(retry, style = Theme.bodyFont, color = Theme.muted)
            }
        }
        AttachmentImageState.Loading, AttachmentImageState.Idle -> Box(
            modifier.fillMaxSize().background(Theme.imagePlaceholder), contentAlignment = Alignment.Center,
        ) {
            if (current == AttachmentImageState.Loading) CircularProgressIndicator(color = Theme.muted)
        }
    }
}
