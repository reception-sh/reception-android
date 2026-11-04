package sh.reception.client

import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.reception.model.TeamMember

/** The cache restore reads, decodes and publishes without holding up the thread that created the profiles. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TeamProfilesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val photos = mapOf(1 to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 2 to Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))

    private fun profiles(cache: TeamProfileCache, download: suspend (String) -> ByteArray? = { null }) = TeamProfiles(
        scope, cache, download, decode = { photos[it.first().toInt()] }, storage = dispatcher, decoding = dispatcher,
    )

    private fun cacheWithTwoPhotos(): TeamProfileCache {
        TeamProfileCache(context, "team-test").apply {
            load()
            update(mapOf("a" to TeamMember("a", "Ann", photoId = "pa"), "b" to TeamMember("b", "Ben", photoId = "pb")), true)
            save(byteArrayOf(1), "pa", "a")
            save(byteArrayOf(2), "pb", "b")
        }
        return TeamProfileCache(context, "team-test")
    }

    @Test
    fun aRestoreOfTwoCachedPhotosPublishesBothWithoutBlocking() {
        val profiles = profiles(cacheWithTwoPhotos())
        assertTrue(profiles.images.value.isEmpty())
        assertTrue(profiles.members.value.isEmpty())

        scope.testScheduler.advanceUntilIdle()

        assertEquals(mapOf("a" to photos[1], "b" to photos[2]), profiles.images.value)
        assertEquals(setOf("Ann", "Ben"), profiles.members.value.values.map { it.name }.toSet())
    }

    @Test
    fun teamDetailsBeforeTheRestoreKeepTheirNamesAndUseOnlyTheMatchingCachedPhoto() {
        val downloads = mutableListOf<String>()
        val profiles = profiles(cacheWithTwoPhotos()) { downloads.add(it); null }
        profiles.update(mapOf(
            "a" to TeamMember("a", "Ann B.", photoUrl = "https://photos/pa", photoId = "pa"),
            "b" to TeamMember("b", "Ben", photoUrl = "https://photos/pb2", photoId = "pb2"),
        ))

        scope.testScheduler.advanceUntilIdle()

        assertEquals(mapOf("a" to photos[1]), profiles.images.value)
        assertEquals("Ann B.", profiles.members.value["a"]?.name)
        assertTrue("https://photos/pb2" in downloads)
    }
}
