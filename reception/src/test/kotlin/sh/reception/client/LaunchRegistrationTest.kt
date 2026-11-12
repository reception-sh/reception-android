package sh.reception.client

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import org.robolectric.Shadows.shadowOf
import sh.reception.Reception
import sh.reception.ReceptionState
import sh.reception.testing.MainLooperDrain
import java.util.Collections

/**
 * The real main dispatcher, whose immediate variant runs inline on the main thread: launch registration must leave
 * the configuring call stack, so an identity token staged right after it is in the registration.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchRegistrationTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val paths = Collections.synchronizedList(mutableListOf<String?>())
    // Robolectric does not run the process lifecycle initializer; the app becomes active by hand.
    private val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    @Before
    fun setUp() {
        SecureStore.keys = SoftwareKey()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path.also { paths += it }) {
                "/v1/devices" -> MockResponse().setResponseCode(201).setBody("""{"token":"t","sessionSecret":"s"}""")
                else -> MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        lifecycle.currentState = Lifecycle.State.CREATED
        ReceptionState.resetForTests()
        server.shutdown()
    }

    /** Runs main-looper work until [condition] holds; storage and network finish on their own threads. */
    private fun mainLoopUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun aTokenStagedRightAfterTheRegistrationStartsIsInItsBody() {
        ReceptionAPI.serviceUrl = server.url("/")
        ReceptionState.configure(context, "app_launchLaunchLaunchLaun")
        val session = requireNotNull(ReceptionState.session)
        mainLoopUntil { session.isPrepared }

        // Like the app coming to the foreground followed by identifyWithToken(jwt) in the same main-thread turn.
        lifecycle.currentState = Lifecycle.State.RESUMED
        session.registerIfNeeded()
        Reception.identifyWithToken(TOKEN)
        mainLoopUntil { "/v1/devices" in paths }

        val registration = generateSequence { server.takeRequest() }.first { it.path == "/v1/devices" }
        assertEquals("POST /v1/devices", "${registration.method} ${registration.path}")
        val body = Json.parseToJsonElement(registration.body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive(TOKEN), body["identityToken"])
    }

    @Test
    fun anAppBackgroundedWhileTheSessionPreparesDoesNotRegister() {
        ReceptionAPI.serviceUrl = server.url("/")
        ReceptionState.configure(context, "app_backgroundBackgroundBa")
        val configured = requireNotNull(ReceptionState.session)
        mainLoopUntil { configured.isPrepared }
        // A session whose preparation waits, like one opening after a logout's deletion.
        val deletion = Job()
        val session = DeviceSession(configured.api, DeviceStore(context, "app_background${System.nanoTime()}"),
            after = deletion)
        lifecycle.currentState = Lifecycle.State.RESUMED
        session.registerIfNeeded()
        mainLoopUntil { session.launchRegistration != null }
        lifecycle.currentState = Lifecycle.State.CREATED
        deletion.complete()
        mainLoopUntil { session.launchRegistration == null }
        assertTrue(session.isPrepared)
        assertFalse("/v1/devices" in paths)
        session.deactivate()
    }

    private companion object {
        /** `{"user_id":"u1"}`; the SDK reads the claim, the service checks the signature. */
        const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyX2lkIjoidTEifQ.c2lnbmF0dXJl"
    }
}
