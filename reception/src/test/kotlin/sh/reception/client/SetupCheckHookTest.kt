package sh.reception.client

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import sh.reception.Reception
import sh.reception.ReceptionInternalApi
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness
import sh.reception.viewmodel.ChatHarness.Companion.APP_ID
import sh.reception.viewmodel.ChatHarness.Companion.CONVERSATION
import sh.reception.viewmodel.ChatHarness.Companion.message
import java.time.Duration

/**
 * A Flutter or React Native host configures after its activity exists, so the lifecycle callbacks never saw that
 * activity's intent; the activity the plugin hands over starts the setup check instead.
 */
@RunWith(RobolectricTestRunner::class)
class SetupCheckHookTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()
    private val application = RuntimeEnvironment.getApplication()
    private var flags = 0

    @Before
    fun setUp() {
        flags = application.applicationInfo.flags
        application.applicationInfo.flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        harness.stop()
        application.applicationInfo.flags = flags
    }

    /** An activity that already exists, created before the SDK registered its callbacks. */
    private fun existingActivity(appId: String): Activity =
        TestActivities.build(Activity::class.java, Intent().putExtra(SetupCheck.EXTRA, appId)).get()

    private fun setupLines(): List<String> =
        ShadowLog.getLogsForTag("Reception").map { it.msg }.filter { it.contains("Setup check") }

    @OptIn(ReceptionInternalApi::class)
    @Test
    fun theActivityAPluginHandsOverStartsTheCheck() {
        harness.respond = { request ->
            if (request.method == "POST" && request.path == "/v1/conversation/messages") {
                val clientId = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["clientId"]?.jsonPrimitive?.content
                harness.json("""{"conversation":$CONVERSATION,"message":${message("server-1", 1, "USER", clientId, SetupCheck.TEXT)}}""", 201)
            } else null
        }
        harness.start()
        Reception.noteCurrentActivity(existingActivity(APP_ID))
        // The check polls with delays, so the main looper's clock has to move.
        harness.mainLoopUntil {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            setupLines().any { it.endsWith("Setup check passed") }
        }
        val sent = harness.requests("POST", "/v1/conversation/messages")
            .map { Json.parseToJsonElement(it.body.readUtf8()).jsonObject["text"]?.jsonPrimitive?.content }
        assertEquals(listOf(SetupCheck.TEXT), sent)
        // Handing over the same or another activity later never starts a second check.
        Reception.noteCurrentActivity(existingActivity(APP_ID))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, harness.requests("POST", "/v1/conversation/messages").size)
    }

    @OptIn(ReceptionInternalApi::class)
    @Test
    fun anotherAppIdFailsWithAppIdMismatch() {
        harness.start()
        Reception.noteCurrentActivity(existingActivity("app_otherOtherOtherOtherOt"))
        assertEquals(listOf("[Reception] Setup check failed: app_id_mismatch"), setupLines())
    }

    @OptIn(ReceptionInternalApi::class)
    @Test
    fun aReleaseHostIgnoresTheExtra() {
        application.applicationInfo.flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        harness.start()
        Reception.noteCurrentActivity(existingActivity("app_otherOtherOtherOtherOt"))
        assertEquals(emptyList<String>(), setupLines())
    }
}
