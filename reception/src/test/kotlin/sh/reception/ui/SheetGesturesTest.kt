package sh.reception.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet's gesture rules: the keyboard lock, Material's release thresholds and the back shrink. */
class SheetGesturesTest {
    @Test
    fun withTheKeyboardUpOnlyTheHeaderMayCloseTheSheet() {
        assertTrue(SheetGestures.locksSheet(keyboardShown = true, onHeader = false))
        assertFalse(SheetGestures.locksSheet(keyboardShown = true, onHeader = true))
    }

    @Test
    fun withTheKeyboardDownAnyDragMayCloseTheSheet() {
        assertFalse(SheetGestures.locksSheet(keyboardShown = false, onHeader = false))
        assertFalse(SheetGestures.locksSheet(keyboardShown = false, onHeader = true))
    }

    @Test
    fun theHeaderIsTheBandFromTheTopEdgeToTheHandleHeight() {
        assertTrue(SheetGestures.isSheetHandle(touchStartingAtY = 64f, handleHeight = 64f))
        assertFalse(SheetGestures.isSheetHandle(touchStartingAtY = 64.5f, handleHeight = 64f))
    }

    @Test
    fun aReleasePastTheDistanceOrFastEnoughDownwardsCloses() {
        assertTrue(SheetGestures.closesOnRelease(offset = 56f, velocity = 0f, height = 1000f, reachedOpen = true,
            closeDistance = 56f, closeVelocity = 125f))
        assertTrue(SheetGestures.closesOnRelease(offset = 10f, velocity = 125f, height = 1000f, reachedOpen = true,
            closeDistance = 56f, closeVelocity = 125f))
        assertFalse(SheetGestures.closesOnRelease(offset = 55f, velocity = 124f, height = 1000f, reachedOpen = true,
            closeDistance = 56f, closeVelocity = 125f))
    }

    @Test
    fun anOpenSheetStaysOpenWhateverTheVelocity() {
        assertFalse(SheetGestures.closesOnRelease(offset = 0f, velocity = 5_000f, height = 1000f, reachedOpen = true,
            closeDistance = 56f, closeVelocity = 125f))
    }

    @Test
    fun anUpwardFlickPastTheDistanceStillClosesAsInMaterial() {
        // Material's anchored settle only looks at the downward velocity threshold, then at the position.
        assertTrue(SheetGestures.closesOnRelease(offset = 80f, velocity = -100f, height = 1000f, reachedOpen = true,
            closeDistance = 56f, closeVelocity = 125f))
    }

    @Test
    fun aSheetCaughtWhileSlidingInOpensUnlessReleasedNearHiddenWithoutAnUpwardFlick() {
        // Material judges from hidden until the sheet reached open: 56 px from hidden is the distance.
        assertFalse(SheetGestures.closesOnRelease(offset = 444f, velocity = 0f, height = 1000f, reachedOpen = false,
            closeDistance = 56f, closeVelocity = 125f))
        assertFalse(SheetGestures.closesOnRelease(offset = 444f, velocity = 2_000f, height = 1000f, reachedOpen = false,
            closeDistance = 56f, closeVelocity = 125f))
        assertTrue(SheetGestures.closesOnRelease(offset = 945f, velocity = 0f, height = 1000f, reachedOpen = false,
            closeDistance = 56f, closeVelocity = 125f))
        assertFalse(SheetGestures.closesOnRelease(offset = 945f, velocity = -125f, height = 1000f, reachedOpen = false,
            closeDistance = 56f, closeVelocity = 125f))
        assertTrue(SheetGestures.closesOnRelease(offset = 1000f, velocity = -5_000f, height = 1000f, reachedOpen = false,
            closeDistance = 56f, closeVelocity = 125f))
    }

    @Test
    fun predictiveBackShrinksBySideUpToTheGivenPixels() {
        assertEquals(1f, SheetGestures.backScale(progress = 0f, size = 1000f, shrink = 48f), 0f)
        assertEquals(0.952f, SheetGestures.backScale(progress = 1f, size = 1000f, shrink = 48f), 1e-6f)
        assertEquals(0.976f, SheetGestures.backScale(progress = 0.5f, size = 1000f, shrink = 48f), 1e-6f)
        assertEquals(0f, SheetGestures.backScale(progress = 1f, size = 20f, shrink = 48f), 0f)
        assertEquals(1f, SheetGestures.backScale(progress = 1f, size = 0f, shrink = 48f), 0f)
    }
}
