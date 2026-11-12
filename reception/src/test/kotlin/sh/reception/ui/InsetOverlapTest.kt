package sh.reception.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A window inset counts only as far as it reaches into the chat, and never twice with what an ancestor handled. */
class InsetOverlapTest {
    @Test
    fun aChatAboveATabBarGetsOnlyThePartOfTheKeyboardThatCoversIt() {
        // Keyboard 900 px from the window's bottom, chat ending 310 px above it (tab bar and navigation bar).
        assertEquals(590, InsetOverlap.overlap(inset = 900, distance = 310, consumed = 0))
        // The navigation bar lies inside the tab bar, below the chat.
        assertEquals(0, InsetOverlap.overlap(inset = 63, distance = 310, consumed = 0))
    }

    @Test
    fun aChatThatReachesTheWindowEdgeGetsTheWholeInset() {
        assertEquals(900, InsetOverlap.overlap(inset = 900, distance = 0, consumed = 0))
    }

    @Test
    fun aHostThatPaddedAndConsumedTheInsetIsNotSubtractedTwice() {
        // Scaffold content padded and consumed by the bottom bar's 310 px: the overlap is what is left, once.
        assertEquals(590, InsetOverlap.overlap(inset = 900, distance = 310, consumed = 310))
        // imePadding() above the chat: the chat ends at the keyboard, nothing is left.
        assertEquals(0, InsetOverlap.overlap(inset = 900, distance = 900, consumed = 900))
    }

    @Test
    fun aConsumedInsetCountsAsHandledEvenWhereTheChatStillReachesTheEdge() {
        // A host that consumed the insets without moving the chat keeps today's result: no padding.
        assertEquals(0, InsetOverlap.overlap(inset = 900, distance = 0, consumed = 900))
    }

    @Test
    fun theEdgesAreTheChatsDistancesFromTheWindowsEdgesAndNeverNegative() {
        assertEquals(WindowEdges(0, 132, 0, 310),
            InsetOverlap.edges(left = 0f, top = 132f, right = 1080f, bottom = 2090f, width = 1080, height = 2400))
        // A sheet dragged below the window, or a chat larger than it, counts as reaching the edge.
        assertEquals(WindowEdges.Window,
            InsetOverlap.edges(left = -4f, top = -10f, right = 1090f, bottom = 2600f, width = 1080, height = 2400))
    }

    @Test
    fun aWindowSizedChatGetsExactlyTheWindowsInsets() {
        val edges = InsetOverlap.edges(left = 0f, top = 0f, right = 1080f, bottom = 2400f, width = 1080, height = 2400)
        assertEquals(WindowEdges.Window, edges)
        assertEquals(63, InsetOverlap.overlap(inset = 63, distance = edges.bottom, consumed = 0))
    }

    @Test
    fun aChatScaledByTheHostPadsInItsOwnPixelsSoThePaddingStillClearsTheKeyboard() {
        // The audit's case: scaled to half its height around its bottom edge, which reaches the window's bottom, with
        // the keyboard over the window's bottom 600 px. 1,200 of its own pixels cover those 600 window pixels.
        val scale = InsetOverlap.scale(along = 1000f, across = 0f, length = 2000)
        assertEquals(0.5f, scale, 0f)
        assertEquals(1200, InsetOverlap.local(InsetOverlap.overlap(inset = 600, distance = 0, consumed = 0), scale))
        // Scaled up, it pads less: 300 of its own pixels cover 600 window pixels.
        assertEquals(300, InsetOverlap.local(600, InsetOverlap.scale(along = 4000f, across = 0f, length = 2000)))
    }

    @Test
    fun anUnscaledChatPadsByTheWindowsPixels() {
        val scale = InsetOverlap.scale(along = 2000f, across = 0f, length = 2000)
        assertEquals(1f, scale, 0f)
        assertEquals(590, InsetOverlap.local(590, scale))
    }

    @Test
    fun aRotatedMirroredOrEmptyChatFallsBackToTheUnscaledRule() {
        // Rotated by 2°: the bottom edge leans 35 px over 1,000 px.
        assertEquals(1f, InsetOverlap.scale(along = 999.4f, across = 34.9f, length = 1000), 0f)
        assertEquals(1f, InsetOverlap.scale(along = -1000f, across = 0f, length = 1000), 0f)
        assertEquals(1f, InsetOverlap.scale(along = 0f, across = 0f, length = 0), 0f)
        // Within half a pixel of its axis it still counts as unrotated.
        assertEquals(0.5f, InsetOverlap.scale(along = 500f, across = 0.4f, length = 1000), 0f)
    }
}
