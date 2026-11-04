package sh.reception.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet rule while the keyboard is up: only a drag from the header closes it. */
class ChatPresenterTest {
    @Test
    fun withTheKeyboardUpOnlyTheHeaderMayCloseTheSheet() {
        assertTrue(ChatPresenter.locksSheet(keyboardVisible = true, onHeader = false))
        assertFalse(ChatPresenter.locksSheet(keyboardVisible = true, onHeader = true))
    }

    @Test
    fun withTheKeyboardDownAnyDragMayCloseTheSheet() {
        assertFalse(ChatPresenter.locksSheet(keyboardVisible = false, onHeader = false))
        assertFalse(ChatPresenter.locksSheet(keyboardVisible = false, onHeader = true))
    }

    @Test
    fun theHeaderIsTheBandFromTheTopEdgeToTheHandleHeight() {
        assertTrue(ChatPresenter.isSheetHandle(touchStartingAtY = 64f, handleHeight = 64f))
        assertFalse(ChatPresenter.isSheetHandle(touchStartingAtY = 64.5f, handleHeight = 64f))
    }
}
