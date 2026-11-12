package sh.reception

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The chat sheet in Material 3's modal bottom sheet values (material3 1.3.1: BottomSheetDefaults, SheetState,
// ModalBottomSheet), so the SDK's own sheet looks and moves like the Material sheet it replaces.

/** The top corners of `BottomSheetDefaults.ExpandedShape`, Material's extra-large corner. */
internal val Theme.sheetShape: RoundedCornerShape
    get() = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** `BottomSheetDefaults.SheetMaxWidth`: wider screens center the sheet. */
internal val Theme.sheetMaxWidth: Dp
    get() = 640.dp

/** `BottomSheetDefaults.ScrimColor`: the color scheme's scrim at the scrim token's opacity. */
internal val Theme.sheetScrim: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)

/** A release this far below the open position closes the sheet (the sheet state's positional threshold). */
internal val Theme.sheetCloseDistance: Dp
    get() = 56.dp

/** A release this fast downwards, per second, closes the sheet (the sheet state's velocity threshold). */
internal val Theme.sheetCloseVelocity: Dp
    get() = 125.dp

/** Opening, closing and settling back. */
internal fun <T> Theme.sheetAnimation(): TweenSpec<T> = tween(durationMillis = 300, easing = FastOutSlowInEasing)

/** How far predictive back narrows the sheet at full progress, and how far it lowers its top. */
internal val Theme.sheetBackShrinkWidth: Dp
    get() = 48.dp

internal val Theme.sheetBackShrinkHeight: Dp
    get() = 24.dp

/** Material's predictive back easing of the gesture's progress. */
internal val Theme.sheetBackEasing: CubicBezierEasing
    get() = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
