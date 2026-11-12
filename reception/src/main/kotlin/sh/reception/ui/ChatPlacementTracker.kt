package sh.reception.ui

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow

/**
 * Measures where a chat sits in the window that shows it now, its edges and its scale, into [placement]: after each
 * layout of the chat (Compose moved, resized or scaled it) and after each layout of its window (a retained composition
 * moved into another window, a window resized around a chat that stayed put). The window is resolved from [view] at
 * each measurement, never kept from the composition, because a `ComposeView` can move between windows with its
 * composition. A Compose transform above the chat (`graphicsLayer`) is followed as it changes, a View transform (a
 * scaled parent view) at the next layout of the chat or its window. Main thread only.
 */
internal class ChatPlacementTracker(private val view: View, private val placement: MutableState<ChatPlacement>) :
    View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
    private var coordinates: LayoutCoordinates? = null
    private var observer: ViewTreeObserver? = null

    fun start() {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    fun stop() {
        view.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(view)
        coordinates = null
    }

    /** The chat was laid out at [coordinates]. */
    fun positioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        measure()
    }

    override fun onGlobalLayout() = measure()

    override fun onViewAttachedToWindow(attached: View) {
        if (observer != null) return
        // Each window has its own observer: the one of the window the view joined, until it leaves it.
        observer = view.viewTreeObserver.also { it.addOnGlobalLayoutListener(this) }
    }

    override fun onViewDetachedFromWindow(detached: View) {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
        observer = null
    }

    private fun measure() {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return
        if (!view.isAttachedToWindow) return
        val window = view.rootView
        val bounds = coordinates.boundsInWindow()
        val size = coordinates.size
        val origin = coordinates.localToWindow(Offset.Zero)
        val across = coordinates.localToWindow(Offset(size.width.toFloat(), 0f)) - origin
        val down = coordinates.localToWindow(Offset(0f, size.height.toFloat())) - origin
        placement.value = ChatPlacement(
            InsetOverlap.edges(bounds.left, bounds.top, bounds.right, bounds.bottom, window.width, window.height),
            scaleX = InsetOverlap.scale(along = across.x, across = across.y, length = size.width),
            scaleY = InsetOverlap.scale(along = down.y, across = down.x, length = size.height),
        )
    }
}
