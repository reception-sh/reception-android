package sh.reception.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.reception.Theme

/** A window is told at most once per keyboard show, whatever its chats and their focus do (A24-1). Times in ms. */
class KeyboardShowsTest {
    private val shows = KeyboardShows()
    private val chat = Any()
    private val otherChat = Any()
    private val settle = Theme.LAYOUT_SETTLE_MILLIS

    /** The keyboard comes up for [chat] at [at]: its composer focuses and the window lays out; the check runs once settled. */
    private fun showFor(chat: Any, at: Long): Long {
        shows.focusChanged(chat, focused = true, now = at)
        assertTrue("a check is due", shows.laidOut(chat, keyboardShown = true, now = at))
        return at + settle
    }

    private fun report(chat: Any, at: Long) {
        assertTrue(shows.mayCheck(chat, keyboardShown = true, now = at))
        shows.reported()
    }

    @Test
    fun theFirstShowIsCheckedOnceTheLayoutSettledAndToldOnce() {
        val check = showFor(chat, at = 0)
        assertFalse("not settled", shows.mayCheck(chat, keyboardShown = true, now = check - 1))
        report(chat, check)
        assertFalse("told once per show", shows.mayCheck(chat, keyboardShown = true, now = check + 1_000))
    }

    @Test
    fun aQuickHideAndShowIsANewShowEvenWhenNoCheckRanInBetween() {
        report(chat, showFor(chat, at = 0))
        // Hidden and shown again within the settle time: the check the hide asked for is replaced by the show's.
        assertTrue(shows.laidOut(chat, keyboardShown = false, now = 1_000))
        assertTrue(shows.laidOut(chat, keyboardShown = true, now = 1_100))
        assertFalse(shows.mayCheck(chat, keyboardShown = true, now = 1_100 + settle - 1))
        assertTrue("the new show may be told again", shows.mayCheck(chat, keyboardShown = true, now = 1_100 + settle))
    }

    @Test
    fun aCheckThatFindsTheKeyboardHiddenEndsTheShow() {
        report(chat, showFor(chat, at = 0))
        assertFalse(shows.mayCheck(chat, keyboardShown = false, now = 2_000))
        shows.laidOut(chat, keyboardShown = true, now = 2_100)
        assertTrue(shows.mayCheck(chat, keyboardShown = true, now = 2_100 + settle))
    }

    @Test
    fun aLayoutWhileACheckWaitsPostponesItAndDecidesForTheNewShow() {
        showFor(chat, at = 0)
        shows.laidOut(chat, keyboardShown = false, now = 100)
        shows.laidOut(chat, keyboardShown = true, now = 200)
        assertFalse("the first check is no longer settled", shows.mayCheck(chat, keyboardShown = true, now = settle))
        report(chat, 200 + settle)
    }

    @Test
    fun losingFocusDoesNotRefillTheBudgetOfTheShow() {
        report(chat, showFor(chat, at = 0))
        assertFalse(shows.focusChanged(chat, focused = false, now = 500))
        assertFalse("an unfocused composer is not checked", shows.mayCheck(chat, keyboardShown = true, now = 1_000))
        showFor(chat, at = 1_000)
        assertFalse("the keyboard never hid: the same show", shows.mayCheck(chat, keyboardShown = true, now = 1_000 + settle))
    }

    @Test
    fun twoComposersInOneWindowShareTheShow() {
        report(chat, showFor(chat, at = 0))
        // Focus moves to the other chat's composer, the keyboard stays: no second report in this show.
        shows.focusChanged(chat, focused = false, now = 500)
        val check = showFor(otherChat, at = 500)
        assertFalse(shows.mayCheck(otherChat, keyboardShown = true, now = check))
        // Only the focused chat is checked, also after the next show.
        shows.laidOut(otherChat, keyboardShown = false, now = 1_000)
        assertFalse("not focused: no check due", shows.laidOut(chat, keyboardShown = true, now = 1_100))
        assertFalse(shows.mayCheck(chat, keyboardShown = true, now = 1_100 + settle))
        assertTrue(shows.mayCheck(otherChat, keyboardShown = true, now = 1_100 + settle))
    }

    @Test
    fun anotherChatLosingFocusDoesNotTakeItFromTheFocusedOne() {
        val check = showFor(chat, at = 0)
        shows.focusChanged(otherChat, focused = false, now = 100)
        assertTrue(shows.mayCheck(chat, keyboardShown = true, now = check + 100))
    }

    @Test
    fun aChatWhoseComposerLeftTheWindowIsNotChecked() {
        // Its view detached (the dialog dismissed while the check waited): it no longer has focus in this window.
        val check = showFor(chat, at = 0)
        shows.focusChanged(chat, focused = false, now = 100)
        assertFalse(shows.mayCheck(chat, keyboardShown = true, now = check + 100))
    }

    @Test
    fun anUnfocusedChatsLayoutDoesNotPostponeTheFocusedChatsCheck() {
        // The same layout reaches the other chat's observer a moment later; only the focused chat schedules a check.
        val check = showFor(chat, at = 0)
        assertFalse(shows.laidOut(otherChat, keyboardShown = true, now = 1))
        assertTrue(shows.mayCheck(chat, keyboardShown = true, now = check))
    }

    @Test
    fun anUnfocusedChatStillSeesTheKeyboardHide() {
        report(chat, showFor(chat, at = 0))
        shows.laidOut(otherChat, keyboardShown = false, now = 1_000)
        shows.laidOut(chat, keyboardShown = true, now = 1_100)
        assertTrue("a new show", shows.mayCheck(chat, keyboardShown = true, now = 1_100 + settle))
    }
}
