package sh.reception.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.appearance
import sh.reception.background
import sh.reception.foreground
import sh.reception.isDark
import sh.reception.sheetMaxWidth
import sh.reception.sheetShape
import sh.reception.strings.Strings
import sh.reception.tertiary
import kotlin.math.roundToInt

/**
 * The chat as a full-height bottom sheet, drawn in the chat activity's own window: Material's modal sheet look and
 * motion ([SheetState], [sheetDrag], [SheetScrim], [SheetBackHandler]) built from stable Compose API only, because
 * Material 3 changes `ModalBottomSheet`'s binary signature with nearly every minor version and hosts run the SDK on
 * their own Material. Every way to close it slides it down, then [onDismissed] runs once. [opensOpen] skips the slide
 * in, for a sheet that comes back to where the person left it.
 */
@Composable
internal fun ChatSheet(dismissRequested: Boolean, onDismissed: () -> Unit, opensOpen: Boolean = false) {
    ChatRoot(embedded = false) {
        val state = rememberSheetState(onDismissed, opensOpen)
        LaunchedEffect(dismissRequested) { if (dismissRequested) state.close() }
        SheetBackHandler(state)
        NavigationBarContrast()
        val title = Theme.appearance.title
        val close = ReceptionLocalization.string(Strings.close)
        val sides = remember { SheetSides() }
        ChatInsetsArea(Modifier.fillMaxSize()) {
            val safeDrawing = chatInsets().safeDrawing
            SheetScrim(state)
            Box(
                // Full height below the safe area's top (status bar, display cutout, caption bar), like a sheet that
                // leaves the app's top edge in view. Not the status bar alone: Android can report a status bar shorter
                // than the cutout (74 against 132 px on an Android 16 emulator), which put the grabber under it.
                Modifier.align(Alignment.TopCenter)
                    .padding(safeDrawing.only(WindowInsetsSides.Top).asPaddingValues())
                    .sheetWidth(Theme.sheetMaxWidth, safeDrawing.only(WindowInsetsSides.Horizontal), sides)
                    .fillMaxSize()
                    .onSizeChanged { state.height = it.height }
                    // Placed from its own measured height, so the very first frame already sits below the window.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.place(0, (state.hidden * placeable.height).roundToInt())
                        }
                    }
                    .sheetBackTransform(state)
                    .semantics {
                        paneTitle = title
                        dismiss(close) { state.requestClose() }
                    }
                    .sheetDrag(state)
                    .clip(Theme.sheetShape)
                    .background(Theme.background)
                    .sheetSideInsets(safeDrawing.only(WindowInsetsSides.Horizontal), sides),
            ) {
                CompositionLocalProvider(LocalContentColor provides Theme.foreground) {
                    Box(Modifier.fillMaxSize().sheetBackContentTransform(state)) {
                        ChatContent(showsSheetControls = true, onClose = state::close, onHeaderHeight = { state.headerHeight = it })
                        Grabber()
                    }
                }
            }
        }
    }
}

/** The sheet's drag indicator in its top inset, above the title bar. */
@Composable
private fun Grabber() {
    Box(Modifier.fillMaxWidth().height(Theme.sheetTopInset), contentAlignment = Alignment.Center) {
        Box(Modifier.size(Theme.sheetGrabberWidth, Theme.sheetGrabberHeight).background(Theme.tertiary, CircleShape))
    }
}

/** The navigation bar lies over the sheet, so its buttons or handle contrast with the sheet's own light or dark. */
@Composable
private fun NavigationBarContrast() {
    val view = LocalView.current
    val dark = Theme.isDark
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
    }
}
