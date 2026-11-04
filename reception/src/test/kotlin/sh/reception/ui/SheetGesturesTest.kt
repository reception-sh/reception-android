package sh.reception.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet rule while the keyboard is up: only a drag from the header closes it. */
class SheetGesturesTest {
    @Test
    fun withTheKeyboardUpOnlyTheHeaderMayCloseTheSheet() {
        assertTrue(SheetGestures.locksSheet(keyboardVisible = true, onHeader = false))
        assertFalse(SheetGestures.locksSheet(keyboardVisible = true, onHeader = true))
    }

    @Test
    fun withTheKeyboardDownAnyDragMayCloseTheSheet() {
        assertFalse(SheetGestures.locksSheet(keyboardVisible = false, onHeader = false))
        assertFalse(SheetGestures.locksSheet(keyboardVisible = false, onHeader = true))
    }

    @Test
    fun theHeaderIsTheBandFromTheTopEdgeToTheHandleHeight() {
        assertTrue(SheetGestures.isSheetHandle(touchStartingAtY = 64f, handleHeight = 64f))
        assertFalse(SheetGestures.isSheetHandle(touchStartingAtY = 64.5f, handleHeight = 64f))
    }
}
