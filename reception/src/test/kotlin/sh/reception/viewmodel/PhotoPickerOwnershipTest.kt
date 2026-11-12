package sh.reception.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker belongs to the launcher that opened it: its result or its disposal ends it, recreation passes it on. */
class PhotoPickerOwnershipTest {
    private val composer = ComposerModel(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun removingTheChatWhileThePickerIsOpenEndsThePicking() {
        val launcher = Any()
        composer.picker.bound(launcher)
        composer.picker.launched(launcher)
        assertTrue(composer.isPicking)
        composer.picker.released(launcher, recreating = false)
        assertFalse(composer.isPicking)
    }

    @Test
    fun aRecreatedLauncherTakesOverTheOpenPickerAndItsResultEndsIt() {
        val before = Any()
        val after = Any()
        composer.picker.launched(before)
        composer.picker.released(before, recreating = true)
        assertTrue(composer.isPicking)
        composer.picker.bound(after)
        // The old launcher is gone; nothing it does changes the new owner's picker.
        composer.picker.finished(before)
        composer.picker.released(before, recreating = false)
        assertTrue(composer.isPicking)
        composer.picker.finished(after)
        assertFalse(composer.isPicking)
    }

    @Test
    fun aResultDeliveredBeforeTheRecreatedLauncherIsBoundStillEndsThePicking() {
        val before = Any()
        composer.picker.launched(before)
        composer.picker.released(before, recreating = true)
        composer.picker.finished(Any())
        assertFalse(composer.isPicking)
    }

    @Test
    fun aSecondLauncherOfTheSameChatLeavesTheOpenPickerAlone() {
        val sheet = Any()
        val embedded = Any()
        composer.picker.bound(sheet)
        composer.picker.bound(embedded)
        composer.picker.launched(sheet)
        composer.picker.released(embedded, recreating = false)
        assertTrue(composer.isPicking)
    }
}
