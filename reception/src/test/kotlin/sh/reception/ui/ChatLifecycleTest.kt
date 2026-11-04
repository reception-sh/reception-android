package sh.reception.ui

import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.reception.ReceptionState
import sh.reception.client.hasConversation
import sh.reception.client.hasStartedChat
import sh.reception.viewmodel.ChatHarness
import sh.reception.viewmodel.ChatHarness.Companion.CONVERSATION
import sh.reception.viewmodel.ChatModel

/** The chat's activity and the app's background decide whether the chat is open and listening. */
@RunWith(RobolectricTestRunner::class)
class ChatLifecycleTest {
    private val harness = ChatHarness()
    private var rotating = false

    @After
    fun tearDown() = harness.stop()

    private fun startedChat(): ChatModel {
        val chat = harness.start()
        harness.session.store.hasStartedChat = true
        harness.session.store.hasConversation = true
        harness.respond = { request ->
            when (request.path?.substringBefore('?')) {
                "/v1/conversation" -> harness.json("""{"conversation":$CONVERSATION,"messages":[]}""")
                "/v1/conversation/unread" -> harness.json("""{"hasConversation":true,"unreadCount":0}""")
                else -> null
            }
        }
        return chat
    }

    private fun lifecycle(chat: ChatModel) = ChatLifecycle(chat, RuntimeEnvironment.getApplication()) { rotating }

    @Test
    fun homeAfterARotationUnderThePickerStopsTheChat() {
        val chat = startedChat()
        val before = lifecycle(chat)
        before.hostChanged(Lifecycle.Event.ON_START)
        before.hostChanged(Lifecycle.Event.ON_RESUME)
        harness.mainLoopUntil { chat.isChatActive }

        val launcher = Any()
        chat.composer.picker.launched(launcher)
        before.hostChanged(Lifecycle.Event.ON_PAUSE)
        rotating = true
        before.hostChanged(Lifecycle.Event.ON_STOP)
        chat.composer.picker.released(launcher, recreating = true)
        before.disposed()
        rotating = false

        // The recreated activity starts under the picker and never resumes.
        val after = lifecycle(chat)
        after.hostChanged(Lifecycle.Event.ON_START)
        assertTrue(chat.isChatActive)
        assertTrue(ReceptionState.isChatOpen.value)

        after.backgrounded()
        assertFalse(chat.isChatActive)
        assertFalse(chat.isVisible)
        assertFalse(ReceptionState.isChatOpen.value)
        assertEquals(null, chat.networkMonitor)
    }

    @Test
    fun theChatShowsAndListensAgainAfterReturningFromTheBackground() {
        val chat = startedChat()
        val lifecycle = lifecycle(chat)
        lifecycle.hostChanged(Lifecycle.Event.ON_START)
        lifecycle.hostChanged(Lifecycle.Event.ON_RESUME)
        harness.mainLoopUntil { chat.isChatActive }
        lifecycle.backgrounded()
        lifecycle.hostChanged(Lifecycle.Event.ON_PAUSE)
        lifecycle.hostChanged(Lifecycle.Event.ON_STOP)
        assertFalse(chat.isVisible)
        lifecycle.hostChanged(Lifecycle.Event.ON_START)
        lifecycle.hostChanged(Lifecycle.Event.ON_RESUME)
        harness.mainLoopUntil { chat.isChatActive }
        assertTrue(chat.isVisible)
        lifecycle.disposed()
    }
}
