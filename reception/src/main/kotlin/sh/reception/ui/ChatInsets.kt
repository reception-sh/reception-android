package sh.reception.ui

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The window insets a chat pads for, each counted only where it overlaps the chat ([InsetOverlap]) and in the chat's
 * own pixels. The only place that reads Compose's window insets; everything in the chat reads these. They follow the
 * keyboard frame by frame, because each value is read where it is used, during layout.
 */
@Stable
internal class ChatInsets(
    window: WindowInsetsOfWindow,
    /** Where the chat sits in its window. */
    val placement: State<ChatPlacement>,
    consumed: State<WindowInsets>,
) {
    val ime: WindowInsets = OverlapInsets(window.ime, placement, consumed)
    val navigationBars: WindowInsets = OverlapInsets(window.navigationBars, placement, consumed)
    val safeDrawing: WindowInsets = OverlapInsets(window.safeDrawing, placement, consumed)
}

/** Compose's insets of the window that shows the composition. */
internal class WindowInsetsOfWindow(
    val ime: WindowInsets,
    val navigationBars: WindowInsets,
    val safeDrawing: WindowInsets,
)

private val LocalChatInsets = staticCompositionLocalOf<ChatInsets?> { null }

/** The insets of the chat around this composition; a screen composed outside a chat counts as covering its window. */
@Composable
internal fun chatInsets(): ChatInsets = LocalChatInsets.current ?: rememberChatInsets(WholeWindow, NothingConsumed)

@Composable
private fun rememberChatInsets(placement: State<ChatPlacement>, consumed: State<WindowInsets>): ChatInsets {
    val window = WindowInsetsOfWindow(WindowInsets.ime, WindowInsets.navigationBars, WindowInsets.safeDrawing)
    return remember(window.ime, window.navigationBars, window.safeDrawing, placement, consumed) {
        ChatInsets(window, placement, consumed)
    }
}

private object WholeWindow : State<ChatPlacement> {
    override val value: ChatPlacement = ChatPlacement.Window
}

private object NothingConsumed : State<WindowInsets> {
    override val value: WindowInsets = WindowInsets(0)
}

/**
 * A chat's own bounds in the window that shows it, measured after layout ([ChatPlacementTracker]), so moving or
 * resizing it (a tab switch, an animated container, another window) updates its insets without new window insets. Its
 * content pads inside these bounds, so the padding never changes what is measured. Every chat surface with a window of
 * its own (the embedded chat, the sheet, the lightbox) is one.
 */
@Composable
internal fun ChatInsetsArea(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val view = LocalView.current
    val placement = remember { mutableStateOf(ChatPlacement.Window) }
    val consumed: MutableState<WindowInsets> = remember { mutableStateOf(WindowInsets(0)) }
    val insets = rememberChatInsets(placement, consumed)
    val tracker = remember(view) { ChatPlacementTracker(view, placement) }
    DisposableEffect(tracker) {
        tracker.start()
        onDispose(tracker::stop)
    }
    Box(modifier.onConsumedWindowInsetsChanged { consumed.value = it }.onGloballyPositioned(tracker::positioned)) {
        val box = this
        CompositionLocalProvider(LocalChatInsets provides insets) { box.content() }
    }
}

/**
 * Whether the keyboard takes room at the window's bottom edge: its inset is above zero, also while it animates. For
 * layout, such as the composer's keyboard metrics: a floating keyboard is shown but needs no room. Gestures ask
 * [keyboardShown] instead. Compose's `WindowInsets.isImeVisible` is experimental; this reads only stable API. Readers
 * recompose when it flips, not on every frame of the keyboard's animation.
 */
@Composable
internal fun keyboardTakesRoom(): Boolean {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    return remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }.value
}

/**
 * Whether the keyboard is shown in this view's window right now, docked or floating (a floating keyboard has no
 * inset). For gesture decisions, read when the gesture needs it: a point-in-time read of the window's current insets
 * needs no listener, so Compose's own inset listener stays the only one.
 */
internal fun View.keyboardShown(): Boolean =
    ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/**
 * One window inset of the window, reduced to its overlap with the chat and converted into the chat's own pixels. What
 * an ancestor consumed counts in window pixels, like the inset it was taken from.
 */
private class OverlapInsets(
    private val window: WindowInsets,
    private val placement: State<ChatPlacement>,
    private val consumed: State<WindowInsets>,
) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int = placement.value.let {
        InsetOverlap.local(InsetOverlap.overlap(window.getLeft(density, layoutDirection), it.edges.left,
            consumed.value.getLeft(density, layoutDirection)), it.scaleX)
    }

    override fun getTop(density: Density): Int = placement.value.let {
        InsetOverlap.local(InsetOverlap.overlap(window.getTop(density), it.edges.top, consumed.value.getTop(density)),
            it.scaleY)
    }

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int = placement.value.let {
        InsetOverlap.local(InsetOverlap.overlap(window.getRight(density, layoutDirection), it.edges.right,
            consumed.value.getRight(density, layoutDirection)), it.scaleX)
    }

    override fun getBottom(density: Density): Int = placement.value.let {
        InsetOverlap.local(InsetOverlap.overlap(window.getBottom(density), it.edges.bottom,
            consumed.value.getBottom(density)), it.scaleY)
    }
}
