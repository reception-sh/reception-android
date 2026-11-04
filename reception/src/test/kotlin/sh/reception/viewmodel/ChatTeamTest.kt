package sh.reception.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.client.Storage
import sh.reception.model.TeamMember

/** A restored chat keeps the cached team until a conversation response names the team. */
@RunWith(RobolectricTestRunner::class)
class ChatTeamTest {
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
}
