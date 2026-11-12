package sh.reception.ui

/**
 * Whether a window swallows the keyboard's inset above the chat's composition, decided from what happened, never from
 * the window's flags: once the chat's composer has focus, the keyboard is shown and the layout has settled
 * ([KeyboardShows.mayCheck]), no keyboard inset reached the composition while the keyboard covers part of the chat. A Compose `Dialog` whose
 * decor fits system windows does that from Android 11 under `adjustNothing` (Android 14: the window's root insets had
 * the keyboard's 809 px, the composition 0), so only a resize can lift the composer there; an edge-to-edge dialog gets
 * the inset, frame by frame, and a window the platform already shrank for the keyboard leaves nothing covered.
 */
internal object SwallowedKeyboardInset {
    /**
     * [compositionInset] is the keyboard's bottom inset as the composition received it, [windowInset] the one of the
     * window's root insets, and [chatBottomEdge] how far the chat's bottom edge sits above the window's, all in window
     * pixels.
     */
    fun isSwallowed(compositionInset: Int, windowInset: Int, chatBottomEdge: Int): Boolean =
        compositionInset == 0 && InsetOverlap.overlap(windowInset, chatBottomEdge, consumed = 0) > 0
}
