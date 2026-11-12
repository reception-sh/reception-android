package sh.reception.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.reception.testing.MainLooperDrain
import java.util.concurrent.CountDownLatch

/** The logout revocation queue reaches the disk before the logout's local reset may delete the credentials. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LogoutRevocationsTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val dispatcher = StandardTestDispatcher()
    private val preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("revocations-test${System.nanoTime()}", Context.MODE_PRIVATE)
    private val key = SoftwareKey()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aLogoutDuringTheInitialLoadFinishesOnlyOnceTheRevocationIsOnDisk() = runTest(dispatcher) {
        // The first disk access (the initial load, started at construction) waits until the test opens it.
        val diskReady = CountDownLatch(1)
        val revocations = LogoutRevocations(SecureStore({ diskReady.await(); preferences }, key))
        val queued = revocations.enqueue(ReceptionAPI(APP_ID, "http://localhost:3123/".toHttpUrl())) { "secret" }
        runCurrent()
        assertFalse(queued.isCompleted)
        diskReady.countDown()
        assertTrue(queued.await())
        // A new process: a fresh store reads the queue from disk.
        val stored = SecureStore(preferences, key).read("pendingSessionRevocations")
        assertEquals(
            SecureStore.ReadResult.Value(
                """[{"appId":"$APP_ID","sessionSecret":"secret","serviceUrl":"http://localhost:3123"}]""",
            ),
            stored,
        )
    }

    private companion object {
        const val APP_ID = "app_lQCXNRFINXc8FEnriGpad3"
    }
}
