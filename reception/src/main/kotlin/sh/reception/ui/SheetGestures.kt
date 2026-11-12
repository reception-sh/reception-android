package sh.reception.ui

/** The rules for the chat sheet's gestures: which touches may close it, when a release closes it, the back shrink. */
internal object SheetGestures {
    /** Whether a touch on the sheet started on its header: the drag handle inset and the title bar. */
    fun isSheetHandle(touchStartingAtY: Float, handleHeight: Float): Boolean = touchStartingAtY <= handleHeight

    /**
     * The iOS sheet rule: while the keyboard is up, only a drag from the header closes the sheet; one over the
     * messages scrolls them or dismisses the keyboard. With the keyboard down, a drag anywhere closes it.
     */
    fun locksSheet(keyboardShown: Boolean, onHeader: Boolean): Boolean = keyboardShown && !onHeader

    /**
     * Whether a drag released [offset] pixels below the open position of a sheet [height] pixels tall, moving at
     * [velocity] pixels per second (downwards positive), closes the sheet: Material's rule, judged from where the sheet
     * comes from. Once it [reachedOpen], a fast enough downward release or one past the distance closes it. A sheet
     * caught while it slid in comes from hidden: it opens unless released within the distance of hidden without a fast
     * enough upward flick. Anything else settles open.
     */
    fun closesOnRelease(
        offset: Float,
        velocity: Float,
        height: Float,
        reachedOpen: Boolean,
        closeDistance: Float,
        closeVelocity: Float,
    ): Boolean = if (reachedOpen) {
        offset > 0f && (velocity >= closeVelocity || offset >= closeDistance)
    } else {
        offset >= height || (velocity > -closeVelocity && offset > height - closeDistance)
    }

    /**
     * The scale predictive back gives one side of the sheet at [progress] (0…1, already eased): it loses up to
     * [shrink] pixels of its [size], never more than all of it.
     */
    fun backScale(progress: Float, size: Float, shrink: Float): Float {
        if (size <= 0f) return 1f
        return 1f - minOf(shrink, size) * progress / size
    }
}
