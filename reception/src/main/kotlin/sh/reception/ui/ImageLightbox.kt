package sh.reception.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import sh.reception.Theme
import sh.reception.lightboxBackground
import sh.reception.model.Attachment
import kotlin.math.abs

/**
 * A confirmed photo full screen on black: pinch to zoom between 1× and 5×, double tap for 3× and back, pan while
 * zoomed, and at 1× a vertical drag past [Theme.lightboxDismissDistance] closes it, like Photos.
 */
@Composable
internal fun ImageLightbox(attachment: Attachment, onDismiss: () -> Unit) {
    LightboxWindow(onDismiss) {
        var scale by remember { mutableFloatStateOf(Theme.MINIMUM_ZOOM) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        fun clamped(value: Offset, zoom: Float): Offset {
            val maximumX = size.width * (zoom - 1) / 2
            val maximumY = size.height * (zoom - 1) / 2
            return Offset(value.x.coerceIn(-maximumX, maximumX), value.y.coerceIn(-maximumY, maximumY))
        }
        val distance = with(LocalDensity.current) { Theme.lightboxDismissDistance.toPx() }
        val fade = minOf(1f, abs(drag.value) / (distance * 2)) * Theme.LIGHTBOX_DRAG_FADE
        // The lightbox is a window of its own, so its insets are measured against it, not against the chat.
        ChatInsetsArea(Modifier.fillMaxSize().onSizeChanged { size = it }.background(Theme.lightboxBackground.copy(alpha = 1f - fade))) {
            RemoteAttachmentImage(
                attachment = attachment,
                fills = false,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > Theme.MINIMUM_ZOOM) Theme.MINIMUM_ZOOM else Theme.DOUBLE_TAP_ZOOM
                            offset = Offset.Zero
                        })
                    }
                    .pointerInput(distance) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            // Movement within the touch slop stays a tap, so the double-tap detector sees it complete.
                            var zoomed = 1f
                            var panned = Offset.Zero
                            var pastTouchSlop = false
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (!pastTouchSlop) {
                                    zoomed *= zoom
                                    panned += pan
                                    val zoomMotion = abs(1 - zoomed) * event.calculateCentroidSize(useCurrent = false)
                                    pastTouchSlop = zoomMotion > viewConfiguration.touchSlop ||
                                        panned.getDistance() > viewConfiguration.touchSlop
                                }
                                if (!pastTouchSlop) continue
                                if (zoom != 1f || scale > Theme.MINIMUM_ZOOM) {
                                    scale = (scale * zoom).coerceIn(Theme.MINIMUM_ZOOM, Theme.MAXIMUM_ZOOM)
                                    offset = clamped(offset + pan, scale)
                                } else {
                                    scope.launch { drag.snapTo(drag.value + pan.y) }
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } while (event.changes.any { it.pressed })
                            if (abs(drag.value) > distance) onDismiss() else scope.launch { drag.animateTo(0f) }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y + drag.value
                    },
            )
            DismissButton(onDismiss, lightbox = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(chatInsets().safeDrawing.asPaddingValues()).padding(Theme.spacing))
        }
    }
}
