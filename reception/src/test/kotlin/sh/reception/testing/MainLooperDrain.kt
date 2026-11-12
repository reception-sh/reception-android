package sh.reception.testing

import android.os.Looper
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Every Robolectric test class has this rule: after the test and its `@After` methods, it runs what the main looper
 * has due now and in the next frames. Robolectric clears the looper's queue after each test, while Compose's
 * `AndroidUiDispatcher.Main` and its global snapshot manager live for the whole sandbox: once any test composed, every
 * snapshot write (the SDK's state, a test's own) posts a dispatch, and the dispatcher remembers that it posted it. One
 * cleared before it ran leaves the dispatcher waiting forever, and no later test of the sandbox recomposes after a
 * state change (fixes 22, A21-3). Timers further out (the SDK's backoffs) are left to the clear.
 */
class MainLooperDrain : ExternalResource() {
    override fun after() = drain()

    companion object {
        private const val FRAMES = 5
        private val FRAME = Duration.ofMillis(16)

        fun drain() {
            val looper = shadowOf(Looper.getMainLooper())
            repeat(FRAMES) { looper.idleFor(FRAME) }
            looper.idle()
        }
    }
}
