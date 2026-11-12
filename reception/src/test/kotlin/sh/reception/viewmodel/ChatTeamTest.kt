package sh.reception.viewmodel

import android.graphics.Bitmap
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import sh.reception.client.Storage
import sh.reception.client.hasStartedChat
import sh.reception.model.TeamMember
import sh.reception.testing.MainLooperDrain
import java.io.ByteArrayOutputStream

/** A restored chat keeps the cached team until a conversation response names the team. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatTeamTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    @Test
    fun restorationKeepsTheCachedTeam() {
        harness.start()
        val cache = harness.session.store.teamProfileCache
        var written = false
        Storage.launch {
            cache.update(mapOf("m1" to TeamMember("m1", "Ada")), showsPhotos = true)
            written = true
        }
        harness.mainLoopUntil { written }
        val chat = ChatModel(harness.session)
        harness.mainLoopUntil { chat.teamProfiles?.members?.value?.isNotEmpty() == true }
        var stored: Set<String> = emptySet()
        Storage.launch { stored = cache.load().keys }
        harness.mainLoopUntil { stored.isNotEmpty() }
        assertEquals(setOf("m1"), stored)
        assertEquals("Ada", chat.teamProfiles?.members?.value?.get("m1")?.name)
        chat.stop()
    }

    @Test
    fun aChatOpenedOfflineKeepsTheCachedNamesAndPhotos() {
        val photo = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        harness.respond = { request ->
            if (request.path?.startsWith("/v1/conversation") == true) {
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            } else {
                null
            }
        }
        // The production path: the session's chat model, restored from a cache written in an earlier run, then opened.
        val chat = harness.start { session ->
            Storage.launch {
                session.store.hasStartedChat = true
                val cache = session.store.teamProfileCache
                cache.update(mapOf("m1" to TeamMember("m1", "Ada", photoId = "p1")), showsPhotos = true)
                cache.save(photo, "p1", "m1")
            }
        }
        chat.markVisible(true)
        chat.activate()
        harness.mainLoopUntil { harness.requests("GET", "/v1/conversation").isNotEmpty() && !chat.isLoading }
        harness.mainLoopUntil { chat.teamProfiles?.images?.value?.isNotEmpty() == true }
        assertTrue(chat.team.isEmpty())
        assertEquals("Ada", chat.teamProfiles?.members?.value?.get("m1")?.name)
        assertTrue("m1" in chat.teamProfiles?.images?.value.orEmpty())
        var stored: Set<String>? = null
        Storage.launch { stored = harness.session.store.teamProfileCache.load().filterValues { it.data != null }.keys }
        harness.mainLoopUntil { stored != null }
        assertEquals(setOf("m1"), stored)
        chat.deactivate(keepsPolling = false)
    }
}
