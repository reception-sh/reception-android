package sh.reception.client

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.reception.model.Attachment
import java.util.Collections

/** The image loader's `403` rule: one resolve and one more download per attempt, then Retry. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AttachmentImageLoaderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val dispatcher = StandardTestDispatcher()
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private var storage: (String) -> MockResponse = { MockResponse().setResponseCode(403) }
    private val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SecureStore.keys = SoftwareKey()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests.add("${request.method} $path ${request.getHeader("Authorization").orEmpty()}".trim())
                return if (path.startsWith("/v1/attachments/")) {
                    MockResponse().setBody("""{"id":"att-1","url":"${server.url("/storage/fresh")}","contentType":"image/jpeg"}""")
                } else {
                    storage(path)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun loader(): AttachmentImageLoader {
        val store = DeviceStore(context, "app_test${System.nanoTime()}")
        store.save(SessionCredentials("secret"))
        val session = DeviceSession(ReceptionAPI("app_0123456789abcdefghijkl", server.url("/")), store, refreshSleep = {})
        session.accessToken = "token"
        val attachment = Attachment("att-1", server.url("/storage/old").toString(), "image/jpeg")
        return AttachmentImageLoader(attachment, session, decode = { image }, isCurrent = { !it.invalidated })
    }

    @Test
    fun aSecond403AfterOneResolveFailsAndRetryResolvesFirst() = runTest(dispatcher) {
        val loader = loader()
        loader.appear()
        assertSame(AttachmentImageState.Failed, loader.state.first { it == AttachmentImageState.Failed })
        assertEquals(listOf("GET /storage/old", "GET /v1/attachments/att-1 Bearer token", "GET /storage/fresh"), requests)

        requests.clear()
        storage = { MockResponse().setBody("jpeg") }
        loader.retry()
        val loaded = loader.state.first { it is AttachmentImageState.Loaded }
        assertSame(image, (loaded as AttachmentImageState.Loaded).bitmap)
        assertEquals(listOf("GET /v1/attachments/att-1 Bearer token", "GET /storage/fresh"), requests)
    }

    @Test
    fun otherStorageFailuresFailWithoutResolving() = runTest(dispatcher) {
        storage = { MockResponse().setResponseCode(404) }
        val loader = loader()
        loader.appear()
        loader.state.first { it == AttachmentImageState.Failed }
        assertEquals(listOf("GET /storage/old"), requests)
    }

    @Test
    fun theLastDisappearingViewCancelsAndInvalidationClears() = runTest(dispatcher) {
        storage = { MockResponse().setBody("jpeg") }
        val loader = loader()
        loader.appear()
        loader.appear()
        loader.disappear()
        loader.state.first { it is AttachmentImageState.Loaded }
        loader.disappear()
        // A loaded photo stays when its last view disappears; session invalidation clears it.
        assertTrue(loader.state.value is AttachmentImageState.Loaded)
        loader.invalidate()
        assertSame(AttachmentImageState.Idle, loader.state.value)
        loader.appear()
        assertSame(AttachmentImageState.Loading, loader.state.value)
        loader.disappear()
        assertSame(AttachmentImageState.Idle, loader.state.value)
    }
}
