package sh.reception.configuration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.reception.client.ReceptionAPI
import sh.reception.testing.MainLooperDrain
import java.util.concurrent.TimeUnit

/** Fetch, ETag, cache and activation against a local HTTP server. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppearanceConfigurationTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val dispatcher = StandardTestDispatcher()
    private var time = 1_000_000L
    private val body = """{"schemaVersion":1,"revision":2,"appearance":{"showsCloseButton":true},"pushPrompt":"offers"}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun configuration(scope: String) = AppearanceConfiguration { time }.apply {
        configure(context, ReceptionAPI(APP_ID, server.url("/")), scope)
    }

    @Test
    fun aFetchIsCachedWithItsETagAndActivatedOnlyWhenTheChatOpens() = runTest(dispatcher) {
        server.enqueue(MockResponse().setBody(body).setHeader("ETag", "\"a\""))
        val configuration = configuration("scope-1")
        configuration.refresh()
        assertEquals("/v1/appearance?platform=android", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertNull(configuration.active)
        configuration.activate()
        assertEquals(true, configuration.active?.showsCloseButton)
        assertEquals(PushPrompt.OFFERS, configuration.activePushPrompt)

        // A new process reads the snapshot and its ETag; the service answers 304 and nothing changes.
        server.enqueue(MockResponse().setResponseCode(304))
        val restarted = configuration("scope-1")
        restarted.refresh()
        assertEquals("\"a\"", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("If-None-Match"))
        // The cached snapshot is active from the start; the refresh waited for it to load.
        assertEquals(true, restarted.active?.showsCloseButton)
        restarted.activate()
        assertEquals(true, restarted.active?.showsCloseButton)
    }

    @Test
    fun requestsAreSpacedAndFailuresKeepTheSnapshot() = runTest(dispatcher) {
        server.enqueue(MockResponse().setBody(body).setHeader("ETag", "\"a\""))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"revision":"x","appearance":{}}"""))
        val configuration = configuration("scope-2")
        configuration.refresh()
        configuration.refresh()
        assertEquals(1, server.requestCount)
        time += 300_000
        configuration.refresh()
        assertEquals(2, server.requestCount)
        configuration.resumed()
        configuration.refresh()
        assertEquals(3, server.requestCount)
        configuration.activate()
        assertEquals(true, configuration.active?.showsCloseButton)
    }

    private companion object {
        const val APP_ID = "app_0123456789abcdefghijkl"
    }
}
