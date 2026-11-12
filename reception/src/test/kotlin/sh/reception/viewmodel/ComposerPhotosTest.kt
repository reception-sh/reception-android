package sh.reception.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Picked photos prepare in the chat's scope with the application context; a later pick joins the queue. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerPhotosTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() {
        scope.cancel()
        TestActivities.closeAll()
    }

    private fun picked(name: String): Uri {
        val uri = Uri.parse("content://photos/$name")
        val jpeg = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(uri, ByteArrayInputStream(jpeg))
        return uri
    }

    @Test
    fun everyPickPreparesEvenAfterThePickingActivityIsGone() {
        val composer = ComposerModel(scope)
        val activity = TestActivities.build(android.app.Activity::class.java).setup()
        composer.add(activity.get(), listOf(picked("a"), picked("b")))
        activity.destroy()
        composer.add(RuntimeEnvironment.getApplication(), listOf(picked("c")))
        assertTrue(composer.loading)
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ChatHarness.WAIT_SECONDS)
        while (composer.loading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertFalse(composer.loading)
        assertEquals(3, composer.photos.size)
        assertFalse(composer.hasPhotoError)
    }
}
