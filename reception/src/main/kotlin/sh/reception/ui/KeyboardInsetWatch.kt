package sh.reception.ui

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import sh.reception.Theme

/** Where the composer reports its focus: an embedded chat's [KeyboardInsetWatch], nothing elsewhere. */
internal val LocalComposerFocus = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** Watches, while this embedded chat is composed, whether its window swallows the keyboard's inset. */
@Composable
internal fun KeyboardInsetCheck(content: @Composable () -> Unit) {
    val view = LocalView.current
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val placement = chatInsets().placement
    val watch = remember(view, ime, density, placement) {
        KeyboardInsetWatch(view, placement) { ime.getBottom(density) }
    }
    DisposableEffect(watch) {
        watch.start()
        onDispose(watch::stop)
    }
    CompositionLocalProvider(LocalComposerFocus provides watch::composerFocused, content = content)
}

/**
 * Tells [EmbeddedKeyboardMode] when the window that shows [view] swallows the keyboard's inset above the composition
 * ([SwallowedKeyboardInset]): after the window lays out for the keyboard, or the composer gains focus with the keyboard
 * already shown, and nothing changed for [Theme.LAYOUT_SETTLE_MILLIS]. [compositionInset] reads the keyboard's inset as
 * the composition received it, [placement] where the chat sits in its window. Only a window that can swallow it is
 * checked ([EmbeddedKeyboardMode.keyboardShows]), at most once per keyboard show however many chats it holds
 * ([KeyboardShows]), so a window that still does not make room is not asked again and again. The window is resolved
 * from [view] each time, as a retained composition can move between windows. Main thread only.
 */
internal class KeyboardInsetWatch(
    private val view: View,
    private val placement: State<ChatPlacement>,
    private val compositionInset: () -> Int,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
    private var observer: ViewTreeObserver? = null
    private var focused = false
    private val settledCheck = Runnable(::check)

    fun start() {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    fun stop() {
        view.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(view)
    }

    fun composerFocused(focused: Boolean) {
        this.focused = focused
        val shows = shows() ?: return
        if (shows.focusChanged(this, focused, SystemClock.uptimeMillis())) {
            checkWhenSettled()
        } else {
            view.removeCallbacks(settledCheck)
        }
    }

    // A window lays out again when its insets change, the keyboard's included.
    override fun onGlobalLayout() {
        val shows = shows() ?: return
        if (shows.laidOut(this, view.keyboardShown(), SystemClock.uptimeMillis())) checkWhenSettled()
    }

    override fun onViewAttachedToWindow(attached: View) {
        if (observer != null) return
        observer = view.viewTreeObserver.also { it.addOnGlobalLayoutListener(this) }
        // A composer that kept its focus while its view moved to this window is checked here.
        if (focused) composerFocused(true)
    }

    override fun onViewDetachedFromWindow(detached: View) {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
        observer = null
        view.removeCallbacks(settledCheck)
        // The window keeps its show for the chats that stay in it; this one's composer is no longer there.
        shows()?.focusChanged(this, focused = false, SystemClock.uptimeMillis())
    }

    private fun shows(): KeyboardShows? = KeyboardWindow.of(view)?.let(EmbeddedKeyboardMode::keyboardShows)

    private fun checkWhenSettled() {
        view.removeCallbacks(settledCheck)
        view.postDelayed(settledCheck, Theme.LAYOUT_SETTLE_MILLIS)
    }

    private fun check() {
        val window = KeyboardWindow.of(view) ?: return
        val shows = EmbeddedKeyboardMode.keyboardShows(window) ?: return
        if (!shows.mayCheck(this, view.keyboardShown(), SystemClock.uptimeMillis())) return
        val swallowed = SwallowedKeyboardInset.isSwallowed(
            compositionInset = compositionInset(),
            windowInset = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0,
            chatBottomEdge = placement.value.edges.bottom,
        )
        if (!swallowed) return
        shows.reported()
        EmbeddedKeyboardMode.keyboardInsetSwallowed(window)
    }
}
