package sh.reception.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sheet's close paths: every close ends in exactly one dismissal, and the lock keeps the person's own closes out.
 * Touches and nested scrolls that meet a sliding sheet stop it, so the position has one writer, and every release
 * ends with the sheet open or closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetStateTest {
    private val test = TestScope(StandardTestDispatcher())

    /** A frame every 16 ms of virtual time, so animations run to their end in the test scheduler. */
    private val frames = object : MonotonicFrameClock {
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            nanos += 16_000_000
            return onFrame(nanos)
        }
    }

    private var dismissals = 0

    private fun sheet(open: Boolean = true) = SheetState(CoroutineScope(test.coroutineContext + frames), open,
        closeDistance = 56f, closeVelocity = 125f, onDismissed = { dismissals += 1 }).apply { height = 1000 }

    @Test
    fun everyClosePathTogetherDismissesOnceAfterTheSlide() {
        val sheet = sheet()
        sheet.close()
        assertFalse(sheet.requestClose())
        sheet.close()
        sheet.settle(velocity = 5_000f)
        assertEquals(0, dismissals)
        test.advanceUntilIdle()
        assertEquals(1, dismissals)
        assertEquals(1f, sheet.hidden, 0f)
    }

    @Test
    fun aLockedSheetSettlesBackFromADragAndRefusesScrimAndBack() {
        val sheet = sheet()
        sheet.locked = true
        sheet.moveBy(300f)
        sheet.settle(velocity = 2_000f)
        assertFalse(sheet.requestClose())
        test.advanceUntilIdle()
        assertEquals(0, dismissals)
        assertEquals(0f, sheet.hidden, 0f)
    }

    @Test
    fun anUnlockedReleasePastTheDistanceCloses() {
        val sheet = sheet()
        sheet.moveBy(60f)
        sheet.settle(velocity = 0f)
        test.advanceUntilIdle()
        assertEquals(1, dismissals)
    }

    @Test
    fun aShortSlowReleaseSettlesBackOpen() {
        val sheet = sheet()
        sheet.moveBy(40f)
        sheet.settle(velocity = 0f)
        test.advanceUntilIdle()
        assertEquals(0, dismissals)
        assertEquals(0f, sheet.hidden, 0f)
    }

    @Test
    fun theCloseButtonAndTheHostCloseALockedSheet() {
        val sheet = sheet()
        sheet.locked = true
        sheet.close()
        test.advanceUntilIdle()
        assertEquals(1, dismissals)
    }

    @Test
    fun theSheetCannotBeDraggedAboveOpenOrBelowHidden() {
        val sheet = sheet()
        assertEquals(0f, sheet.moveBy(-50f), 0f)
        assertEquals(1000f, sheet.moveBy(5_000f), 0f)
        assertEquals(1f, sheet.hidden, 0f)
    }

    @Test
    fun aNewSheetSlidesInFromBelow() {
        val sheet = sheet(open = false)
        assertEquals(1f, sheet.hidden, 0f)
        test.launch(frames) { sheet.show() }
        test.advanceUntilIdle()
        assertEquals(0f, sheet.hidden, 0f)
    }

    /** Starts [sheet]'s enter animation and runs it for [millis] of frames. */
    private fun enter(sheet: SheetState, millis: Long) {
        test.launch(frames) { sheet.show() }
        test.advanceTimeBy(millis)
        test.runCurrent()
    }

    /** One more frame of virtual time. */
    private fun nextFrame() {
        test.advanceTimeBy(16)
        test.runCurrent()
    }

    @Test
    fun anUpwardNestedScrollDuringTheEnterAnimationStopsItAndTheReleaseLeavesTheSheetOpen() {
        // The audit's trace: the next frame put the sheet back to 335 px, and the release closed it.
        val sheet = sheet(open = false)
        enter(sheet, millis = 128)
        assertTrue(sheet.offset > 300f)
        val connection = SheetScrollConnection(sheet)
        connection.onPreScroll(Offset(0f, -sheet.offset), NestedScrollSource.UserInput)
        assertEquals(0f, sheet.offset, 0f)
        nextFrame()
        assertEquals(0f, sheet.offset, 0f)
        test.launch { connection.onPreFling(Velocity.Zero) }
        test.advanceUntilIdle()
        assertEquals(0, dismissals)
        assertEquals(0f, sheet.hidden, 0f)
    }

    @Test
    fun aShortNestedScrollDuringTheEnterAnimationLeavesTheSheetWhereItIsUntilTheReleaseOpensIt() {
        val sheet = sheet(open = false)
        enter(sheet, millis = 128)
        val moved = sheet.offset - 10f
        sheet.moveBy(-10f)
        nextFrame()
        assertEquals(moved, sheet.offset, 0.01f)
        assertFalse(sheet.isSliding)
        // Caught on its way in, the sheet comes from hidden: far from it, a release opens it.
        sheet.settle(velocity = 0f)
        test.advanceUntilIdle()
        assertEquals(0, dismissals)
        assertEquals(0f, sheet.hidden, 0f)
    }

    @Test
    fun aTouchThatCatchesTheEnteringSheetAndLiftsWithoutMovingOpensIt() {
        val sheet = sheet(open = false)
        enter(sheet, millis = 128)
        assertTrue(sheet.isSliding)
        // The draggable starts its drag at once on a sliding sheet and ends it without a delta.
        test.launch { sheet.draggable.drag(MutatePriority.UserInput) {} }
        test.runCurrent()
        val caught = sheet.offset
        assertFalse(sheet.isSliding)
        nextFrame()
        assertEquals(caught, sheet.offset, 0f)
        sheet.settle(velocity = 0f)
        test.advanceUntilIdle()
        assertEquals(0, dismissals)
        assertEquals(0f, sheet.hidden, 0f)
    }

    @Test
    fun anEnteringSheetCaughtAndReleasedNearHiddenCloses() {
        val sheet = sheet(open = false)
        enter(sheet, millis = 128)
        sheet.moveBy(990f - sheet.offset)
        sheet.settle(velocity = 0f)
        test.advanceUntilIdle()
        assertEquals(1, dismissals)
        assertEquals(1f, sheet.hidden, 0f)
    }

    @Test
    fun aNestedScrollDuringASettleStopsItAndTheReleaseSettlesAgain() {
        val sheet = sheet()
        sheet.locked = true
        sheet.moveBy(300f)
        sheet.settle(velocity = 0f)
        test.advanceTimeBy(64)
        test.runCurrent()
        assertTrue(sheet.isSliding)
        val connection = SheetScrollConnection(sheet)
        val moved = sheet.offset + 20f
        connection.onPostScroll(Offset.Zero, Offset(0f, 20f), NestedScrollSource.UserInput)
        nextFrame()
        assertEquals(moved, sheet.offset, 0.01f)
        test.launch { connection.onPreFling(Velocity.Zero) }
        test.advanceUntilIdle()
        assertEquals(0, dismissals)
        assertEquals(0f, sheet.hidden, 0f)
    }

    @Test
    fun aNestedDragThatMovedTheSheetSettlesItOnReleaseAndKeepsItsVelocityFromTheList() {
        val sheet = sheet()
        val connection = SheetScrollConnection(sheet)
        connection.onPostScroll(Offset.Zero, Offset(0f, 40f), NestedScrollSource.UserInput)
        var left = Velocity.Zero
        test.launch { left = Velocity(0f, 50f) - connection.onPreFling(Velocity(0f, 50f)) }
        test.advanceUntilIdle()
        assertEquals(Velocity.Zero, left)
        assertEquals(0f, sheet.hidden, 0f)

        connection.onPostScroll(Offset.Zero, Offset(0f, 40f), NestedScrollSource.UserInput)
        test.launch { connection.onPreFling(Velocity(0f, 2_000f)) }
        test.advanceUntilIdle()
        assertEquals(1, dismissals)
        assertEquals(1f, sheet.hidden, 0f)
    }

    @Test
    fun aListFlingThatNeverMovedTheSheetLeavesItAlone() {
        val sheet = sheet()
        val connection = SheetScrollConnection(sheet)
        connection.onPostScroll(Offset.Zero, Offset.Zero, NestedScrollSource.UserInput)
        var consumed = Velocity(1f, 1f)
        test.launch { consumed = connection.onPreFling(Velocity(0f, 3_000f)) }
        test.advanceUntilIdle()
        assertEquals(Velocity.Zero, consumed)
        assertFalse(sheet.isSliding)
        assertEquals(0, dismissals)
    }

    @Test
    fun aCloseIsNotInterruptedByATouchOrANestedScrollAndDismissesOnce() {
        val sheet = sheet()
        sheet.close()
        test.advanceTimeBy(64)
        test.runCurrent()
        assertFalse(sheet.isSliding)
        assertEquals(0f, sheet.moveBy(-100f), 0f)
        test.launch { sheet.draggable.drag(MutatePriority.UserInput) { dragBy(-100f) } }
        test.runCurrent()
        sheet.settle(velocity = -5_000f)
        test.advanceUntilIdle()
        assertEquals(1, dismissals)
        assertEquals(1f, sheet.hidden, 0f)
    }

    @Test
    fun backDuringATouchClosesTheUnlockedSheetOnceAndLeavesALockedOneToTheTouch() {
        val sheet = sheet()
        val touch = test.launch { sheet.draggable.drag(MutatePriority.UserInput) { dragBy(200f); awaitCancellation() } }
        test.runCurrent()
        sheet.locked = true
        assertFalse(sheet.requestClose())
        test.runCurrent()
        assertEquals(200f, sheet.offset, 0f)
        touch.cancel()
        sheet.settle(velocity = 0f)
        test.advanceUntilIdle()
        assertEquals(0f, sheet.hidden, 0f)

        sheet.locked = false
        test.launch { sheet.draggable.drag(MutatePriority.UserInput) { dragBy(200f); awaitCancellation() } }
        test.runCurrent()
        assertTrue(sheet.requestClose())
        // The close preempts the touch; the draggable then reports its end, which changes nothing.
        test.runCurrent()
        sheet.settle(velocity = 0f)
        test.advanceUntilIdle()
        assertEquals(1, dismissals)
        assertEquals(1f, sheet.hidden, 0f)
    }
}
