package sh.reception.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.client.draftText
import sh.reception.testing.MainLooperDrain

/** The stored draft is restored only into an untouched composer; text written before restoration wins and is stored. */
@RunWith(RobolectricTestRunner::class)
class ChatDraftTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    @Test
    fun textWrittenBeforeRestorationWinsAndIsStored() {
        harness.start()
        harness.session.store.draftText = "stored"
        val chat = ChatModel(harness.session)
        chat.composer.text = "typed"
        harness.mainLoopUntil { chat.isRestored }
        assertEquals("typed", chat.composer.text)
        assertEquals("typed", harness.session.store.draftText)
        chat.stop()
    }

    @Test
    fun anUntouchedComposerGetsTheStoredDraft() {
        harness.start()
        harness.session.store.draftText = "stored"
        val chat = ChatModel(harness.session)
        harness.mainLoopUntil { chat.isRestored }
        assertEquals("stored", chat.composer.text)
        chat.stop()
    }
}
