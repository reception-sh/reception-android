package sh.reception.viewmodel

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import sh.reception.viewmodel.ChatHarness.Companion.WAIT_SECONDS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds the server's answers until the test [open]s it, so the test can see a request in flight. A hold that outlasts
 * [WAIT_SECONDS] is a failure, not a release: the request gets a dropped connection instead of its answer, and
 * [ChatHarness.stop] fails the test. Create gates with [ChatHarness.gate], which opens them all when the test ends.
 */
internal class ResponseGate {
    private val opened = CountDownLatch(1)
    private val holding = AtomicInteger()

    /** Requests waiting at the gate now. */
    val held: Int
        get() = holding.get()

    @Volatile
    var expired: Boolean = false
        private set

    /** On the server's thread: [response] once the gate opens. */
    fun answer(response: () -> MockResponse): MockResponse {
        holding.incrementAndGet()
        val passed = try {
            opened.await(WAIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            holding.decrementAndGet()
        }
        if (passed) return response()
        expired = true
        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
    }

    fun open() = opened.countDown()
}
