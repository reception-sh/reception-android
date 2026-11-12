package sh.reception

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.reception.client.SecureStore
import sh.reception.client.SoftwareKey
import sh.reception.testing.MainLooperDrain

/** On Android 7.0 the notice about the missing Let's Encrypt roots comes once per process, whatever is configured. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class LegacyTrustNoticeTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val lines = mutableListOf<String>()

    @After
    fun tearDown() {
        Log.handler = null
        ReceptionState.resetForTests()
    }

    @Test
    fun theNoticeComesOncePerProcess() {
        SecureStore.keys = SoftwareKey()
        Log.handler = { _, line -> lines += line }
        val application = RuntimeEnvironment.getApplication()
        for (appId in listOf(FIRST, SECOND, FIRST)) ReceptionState.configure(application, appId)
        assertEquals(3, lines.count { it.startsWith("Configured") })
        assertEquals(1, lines.count { it.startsWith("Android 7.0 has no Let's Encrypt root") })
    }

    private companion object {
        const val FIRST = "app_trustTrustTrustTrust01"
        const val SECOND = "app_trustTrustTrustTrust02"
    }
}
