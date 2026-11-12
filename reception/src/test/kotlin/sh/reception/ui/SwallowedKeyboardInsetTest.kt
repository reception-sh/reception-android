package sh.reception.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A window swallows the keyboard's inset only when it covers the chat and none of it reached the composition. */
class SwallowedKeyboardInsetTest {
    private fun swallowed(compositionInset: Int = 0, windowInset: Int = 809, chatBottomEdge: Int = 0) =
        SwallowedKeyboardInset.isSwallowed(compositionInset, windowInset, chatBottomEdge)

    @Test
    fun aKeyboardThatCoversTheChatWithoutReachingTheCompositionIsSwallowed() {
        // Android 14, a dialog whose decor fits system windows: root insets 809 px, the composition 0.
        assertTrue(swallowed())
        // A chat that ends above the window's bottom edge, still partly covered.
        assertTrue(swallowed(chatBottomEdge = 300))
    }

    @Test
    fun anInsetThatReachedTheCompositionIsNotSwallowed() {
        // An edge-to-edge dialog: the inset arrives frame by frame.
        assertFalse(swallowed(compositionInset = 1))
        assertFalse(swallowed(compositionInset = 809))
    }

    @Test
    fun aKeyboardThatCoversNothingOfTheChatIsNotSwallowed() {
        // The platform already shrank the window (Android 11), or the keyboard floats.
        assertFalse(swallowed(windowInset = 0))
        // The chat ends above the keyboard's top edge.
        assertFalse(swallowed(chatBottomEdge = 809))
        assertFalse(swallowed(chatBottomEdge = 900))
    }
}
