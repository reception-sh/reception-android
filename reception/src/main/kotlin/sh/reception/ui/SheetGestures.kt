package sh.reception.ui

/** The iOS sheet rule for drags on the chat sheet: which touches may close it. */
internal object SheetGestures {
    /** Whether a touch on the sheet started on its header: the drag handle inset and the title bar. */
    fun isSheetHandle(touchStartingAtY: Float, handleHeight: Float): Boolean = touchStartingAtY <= handleHeight

    /**
     * While the keyboard is up, only a drag from the header closes the sheet; one over the messages scrolls them or
     * dismisses the keyboard. With the keyboard down, a drag anywhere closes it.
     */
    fun locksSheet(keyboardVisible: Boolean, onHeader: Boolean): Boolean = keyboardVisible && !onHeader
}
