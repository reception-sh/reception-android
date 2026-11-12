package sh.reception.client

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.ReceptionState
import sh.reception.testing.MainLooperDrain

/** Event names and the rule that opens the chat only within five seconds of `track` (SDK_SPEC.md, Automations). */
@RunWith(RobolectricTestRunner::class)
class AutomationsTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private var now = 0L

    /** Settles once [settlesAt] is reached; every look at it takes [step] of time. */
    private inner class Presenter(val settlesAt: Long, val step: Long = 500, val presents: Boolean = true) : AutomationPresenter {
        var presented = 0
        override val isSettled: Boolean
            get() {
                now += step
                return now >= settlesAt
            }

        override suspend fun present(stillWanted: () -> Boolean): Boolean {
            presented += 1
            return presents
        }
    }

    @After
    fun closeChat() {
        ReceptionState.isChatOpen.value = false
    }

    /** The open rule as `track` starts it at time 0, with the revisions of now. */
    private suspend fun Automations.openNow(arriving: Boolean, presentation: Int = ReceptionState.presentationRevision) =
        openChatWhenSettled(start = 0, presentation, ReceptionState.closeRevision, arriving)

    @Test
    fun aResetForTheNextTestCancelsTheWorkStillRunning() {
        // An open that waits for an app that never settles, as a test's track can leave behind.
        val automations = Automations(Presenter(settlesAt = Long.MAX_VALUE, step = 0), clock = { now })
        val waiting = automations.scope.launch { automations.openNow(arriving = true) }
        assertTrue(waiting.isActive)
        automations.resetForTests()
        assertTrue("nothing of one test runs on into the next", waiting.isCancelled)
    }

    @Test
    fun eventNamesUseUpTo64LettersDigitsUnderscoresDotsOrHyphens() {
        assertTrue(Automations.accepts("paywall_declined"))
        assertTrue(Automations.accepts("a.b-c_9"))
        assertTrue(Automations.accepts("e".repeat(64)))
        assertFalse(Automations.accepts(""))
        assertFalse(Automations.accepts("e".repeat(65)))
        assertFalse(Automations.accepts("paywall declined"))
        assertFalse(Automations.accepts("ümlaut"))
        assertFalse(Automations.accepts("a/b"))
    }

    @Test
    fun theChatOpensOnceTheAppSettlesWithinFiveSeconds() = runBlocking {
        val presenter = Presenter(settlesAt = 4_000)
        val automations = Automations(presenter, clock = { now })
        automations.openNow(arriving = true)
        assertEquals(1, presenter.presented)
        // The first load after it reveals the arriving message, once.
        assertTrue(automations.takeOpenedChat())
        assertFalse(automations.takeOpenedChat())
    }

    @Test
    fun anAppThatSettlesLaterNeverSeesTheChat() = runBlocking {
        val presenter = Presenter(settlesAt = 5_500)
        val automations = Automations(presenter, clock = { now })
        automations.openNow(arriving = true)
        assertEquals(0, presenter.presented)
        assertFalse(automations.takeOpenedChat())
    }

    @Test
    fun aRevealOpportunityExpiresWithTheWindow() = runBlocking {
        val automations = Automations(Presenter(settlesAt = 0), clock = { now })
        automations.openNow(arriving = true)
        now += Automations.OPEN_WINDOW_MILLIS
        assertFalse(automations.takeOpenedChat())
    }

    @Test
    fun anOpenChatOrANewPresentationRevisionOpensNothing() = runBlocking {
        val presenter = Presenter(settlesAt = 0)
        val automations = Automations(presenter, clock = { now })
        automations.openNow(arriving = true, presentation = ReceptionState.presentationRevision + 1)
        ReceptionState.isChatOpen.value = true
        automations.openNow(arriving = false)
        assertEquals(0, presenter.presented)
    }

    @Test
    fun aRefusedPresentationIsTriedAgainWhileTheWindowLasts() = runBlocking {
        val presenter = Presenter(settlesAt = 0, step = 1_000, presents = false)
        val automations = Automations(presenter, clock = { now })
        automations.openNow(arriving = false)
        // Tried at 0, 1, 2, 3 and 4 seconds after `track`.
        assertEquals(5, presenter.presented)
    }
}
