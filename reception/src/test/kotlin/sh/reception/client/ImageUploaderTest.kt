package sh.reception.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** Photo preparation bounds and the reservation and storage PUT contract. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageUploaderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SecureStore.keys = SoftwareKey()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun jpeg(bitmap: Bitmap, quality: Int = 95): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** Noise compresses badly, so the quality and size steps really run. */
    private fun noise(width: Int, height: Int): ByteArray {
        val random = Random(7)
        val pixels = IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }
        return jpeg(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
    }

    @Test
    fun aLargePhotoIsScaledTo2048PixelsAndAtMostOneMebibyte() {
        val source = noise(4000, 3000)
        assertTrue(source.size > ImagePreparation.MAXIMUM_BYTES)
        val prepared = ImagePreparation.prepare(source)
        assertTrue("${prepared.width}x${prepared.height}", maxOf(prepared.width, prepared.height) <= 2048)
        assertEquals(4f / 3, prepared.width.toFloat() / prepared.height, 0.01f)
        assertTrue("${prepared.data.size} bytes", prepared.data.size <= 1_048_576)
        val decoded = BitmapFactory.decodeByteArray(prepared.data, 0, prepared.data.size)
        assertEquals(prepared.width, decoded.width)
        assertEquals(prepared.height, decoded.height)
    }

    @Test
    fun exifOrientationIsAppliedBeforeScaling() {
        // Left half red, right half blue; EXIF says "rotate 90° clockwise to display".
        val pixels = IntArray(400 * 200) { if (it % 400 < 200) Color.RED else Color.BLUE }
        val file = File(context.cacheDir, "rotated.jpg")
        file.writeBytes(jpeg(Bitmap.createBitmap(pixels, 400, 200, Bitmap.Config.ARGB_8888)))
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()) }.saveAttributes()
        val prepared = ImagePreparation.prepare(file.readBytes())
        assertEquals(200, prepared.width)
        assertEquals(400, prepared.height)
        val upright = BitmapFactory.decodeByteArray(prepared.data, 0, prepared.data.size)
        assertTrue(Color.red(upright.getPixel(100, 50)) > 200 && Color.blue(upright.getPixel(100, 50)) < 60)
        assertTrue(Color.blue(upright.getPixel(100, 350)) > 200 && Color.red(upright.getPixel(100, 350)) < 60)
    }

    @Test
    fun theReservationDescribesTheBytesAndThePutSendsExactlyTheReturnedHeaders() = runTest(dispatcher) {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"attachmentId":"att_1","uploadUrl":"${server.url("/storage/put?sig=1")}",
            "headers":{"content-type":"image/jpeg","if-match":"\"etag\"","x-amz-meta-checksum":"abc"}}"""))
        server.enqueue(MockResponse().setResponseCode(412))
        val store = DeviceStore(context, "app_test${System.nanoTime()}")
        store.save(SessionCredentials("secret"))
        val session = DeviceSession(ReceptionAPI("app_0123456789abcdefghijkl", server.url("/")), store, refreshSleep = {})
        session.accessToken = "token"
        val progress = mutableListOf<Double>()
        val id = ImageUploader.upload(noise(300, 200), "MESSAGE", 1, session) { progress.add(it) }
        assertEquals("att_1", id)
        val reservation = server.takeRequest()
        assertEquals("POST /v1/uploads", "${reservation.method} ${reservation.path}")
        val input = Json.parseToJsonElement(reservation.body.readUtf8()).jsonObject
        assertEquals(setOf("requestId", "contentType", "bytes", "width", "height", "checksum"), input.keys)
        assertEquals("image/jpeg", input.getValue("contentType").jsonPrimitive.content)
        val put = server.takeRequest()
        val body = put.body.readByteArray()
        assertEquals("PUT /storage/put?sig=1", "${put.method} ${put.path}")
        assertEquals("image/jpeg", put.getHeader("Content-Type"))
        assertEquals("\"etag\"", put.getHeader("If-Match"))
        assertEquals("abc", put.getHeader("x-amz-meta-checksum"))
        assertEquals(null, put.getHeader("Authorization"))
        assertEquals(body.size, input.getValue("bytes").jsonPrimitive.content.toInt())
        assertEquals(body.toByteString().sha256().base64(), input.getValue("checksum").jsonPrimitive.content)
        assertEquals(ImageUploader.requestId("MESSAGE", 1, body.toByteString().sha256().base64()),
            input.getValue("requestId").jsonPrimitive.content)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1.0, progress.last(), 0.0)
    }

    @Test
    fun theRequestIdIsAStableVersion5Uuid() {
        val id = ImageUploader.requestId("A", 0, "c2hh")
        assertEquals(id, ImageUploader.requestId("A", 0, "c2hh"))
        assertTrue(id != ImageUploader.requestId("A", 1, "c2hh"))
        assertTrue(Regex("^[0-9A-F]{8}-[0-9A-F]{4}-5[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$").matches(id))
    }
}
