package sh.reception.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import sh.reception.Theme
import sh.reception.sheetBackEasing
import sh.reception.sheetBackShrinkHeight
import sh.reception.sheetBackShrinkWidth

/**
 * System Back closes the sheet with its slide. On Android 14+ the back gesture first shrinks it towards its bottom
 * edge, as Material's sheet does, and a cancelled gesture lets it grow back. A locked sheet stays (and grows back);
 * Back while the sheet is closing does nothing more.
 */
@Composable
internal fun SheetBackHandler(state: SheetState) {
    PredictiveBackHandler { progress ->
        try {
            progress.collect { state.back.snapTo(Theme.sheetBackEasing.transform(it.progress)) }
            if (!state.requestClose() && !state.closing) state.cancelBack()
        } catch (cancelled: CancellationException) {
            state.cancelBack()
            throw cancelled
        }
    }
}

/** The sheet's predictive back shrink, around the bottom of the open sheet. */
internal fun Modifier.sheetBackTransform(state: SheetState): Modifier = graphicsLayer {
    val height = size.height
    if (height <= 0f) return@graphicsLayer
    scaleX = SheetGestures.backScale(state.back.value, size.width, Theme.sheetBackShrinkWidth.toPx())
    scaleY = SheetGestures.backScale(state.back.value, height, Theme.sheetBackShrinkHeight.toPx())
    transformOrigin = TransformOrigin(0.5f, (state.offset + height) / height)
}

/** Inside the shrunk sheet, the content keeps its proportions from the sheet's top edge down. */
internal fun Modifier.sheetBackContentTransform(state: SheetState): Modifier = graphicsLayer {
    val scaleX = SheetGestures.backScale(state.back.value, size.width, Theme.sheetBackShrinkWidth.toPx())
    val scaleY = SheetGestures.backScale(state.back.value, size.height, Theme.sheetBackShrinkHeight.toPx())
    this.scaleY = if (scaleY != 0f) scaleX / scaleY else 1f
    transformOrigin = TransformOrigin(0.5f, 0f)
}
