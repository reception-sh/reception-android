package sh.reception.client

import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import sh.reception.Reception
import sh.reception.ReceptionState

/**
 * The real main dispatcher, whose immediate variant runs inline on the main thread: launch registration must leave
 * the configuring call stack, so an identity token staged right after it is in the registration.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchRegistrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        SecureStore.keys = SoftwareKey()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/devices" -> MockResponse().setResponseCode(201).setBody("""{"token":"t","sessionSecret":"s"}""")
                else -> MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
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

        // Like configure() at launch followed by identifyWithToken(jwt) in the same main-thread turn.
        session.registerIfNeeded()
        Reception.identifyWithToken(TOKEN)
        mainLoopUntil { server.requestCount > 0 }

        val registration = requireNotNull(server.takeRequest())
        assertEquals("POST /v1/devices", "${registration.method} ${registration.path}")
        val body = Json.parseToJsonElement(registration.body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive(TOKEN), body["identityToken"])
    }

    private companion object {
        /** `{"user_id":"u1"}`; the SDK reads the claim, the service checks the signature. */
        const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyX2lkIjoidTEifQ.c2lnbmF0dXJl"
    }
}
