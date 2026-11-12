package sh.reception.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.Reception
import sh.reception.testing.MainLooperDrain
import sh.reception.viewmodel.ChatHarness
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** `closeChat()` after `track` withdraws the automation's open, also while the event's request is still in flight. */
@RunWith(RobolectricTestRunner::class)
class AutomationCloseTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()
    private val answer = CountDownLatch(1)

    private val presenter = object : AutomationPresenter {
        var presented = 0
        override val isSettled = true
        override suspend fun present(stillWanted: () -> Boolean): Boolean {
            presented += 1
            return true
        }
    }

    @After
    fun tearDown() {
        answer.countDown()
        harness.stop()
    }

    @Test
    fun aCloseWhileTheEventIsInFlightKeepsTheChatClosed() {
        harness.respond = { request ->
            when (request.path?.substringBefore('?')) {
                "/v1/automations" -> harness.json("""{"events":["opening_event"]}""")
                "/v1/automations/events" -> {
                    answer.await(5, TimeUnit.SECONDS)
                    harness.json("""{"sent":true,"openChat":true}""")
                }
                else -> null
            }
        }
        harness.start()
        val automations = Automations(presenter)
        automations.track("opening_event", opensChat = false, session = harness.session)
        harness.mainLoopUntil { harness.requests("POST", "/v1/automations/events").isNotEmpty() }

        Reception.closeChat()
        answer.countDown()
        harness.mainLoopUntil { harness.requests("GET", "/v1/conversation/unread").size >= 2 }
        harness.settle()
        assertEquals(0, presenter.presented)
    }
}
