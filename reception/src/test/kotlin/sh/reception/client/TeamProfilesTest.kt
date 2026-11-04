package sh.reception.client

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.reception.model.TeamMember
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The cache restore reads, decodes and publishes without holding up the thread that created the profiles. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TeamProfilesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val photos = mapOf(1 to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 2 to Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))

    // A storage thread the test can hold: a block queued first keeps everything after it waiting until [releaseStorage].
    private val storageThread = Executors.newSingleThreadExecutor()
    private val heldStorage = storageThread.asCoroutineDispatcher()
    private val storageHeld = CountDownLatch(1)

    private fun profiles(
        cache: TeamProfileCache,
        scope: CoroutineScope = this.scope,
        storage: CoroutineDispatcher = dispatcher,
        download: suspend (String) -> ByteArray? = { null },
    ) = TeamProfiles(scope, cache, download, decode = { photos[it.first().toInt()] }, storage = storage, decoding = dispatcher)

    private fun holdStorage() = storageThread.execute { storageHeld.await() }

    /** Lets the held storage thread run and waits until everything queued on it has finished. */
    private fun releaseStorage() {
        storageHeld.countDown()
        storageThread.submit {}.get(5, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        storageHeld.countDown()
        storageThread.shutdown()
    }

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
        val profiles = profiles(cacheWithTwoPhotos(), download = { downloads.add(it); null })
        profiles.update(mapOf(
            "a" to TeamMember("a", "Ann B.", photoUrl = "https://photos/pa", photoId = "pa"),
            "b" to TeamMember("b", "Ben", photoUrl = "https://photos/pb2", photoId = "pb2"),
        ))

        scope.testScheduler.advanceUntilIdle()

        assertEquals(mapOf("a" to photos[1]), profiles.images.value)
        assertEquals("Ann B.", profiles.members.value["a"]?.name)
        assertTrue("https://photos/pb2" in downloads)
    }

    @Test
    fun aCachedPhotoRestoredAfterItsDownloadFailedIsStillUsed() {
        holdStorage()
        val profiles = profiles(cacheWithTwoPhotos(), storage = heldStorage, download = { null })
        profiles.update(mapOf("a" to TeamMember("a", "Ann", photoUrl = "https://photos/pa", photoId = "pa")))
        // The download fails before the cache has loaded.
        scope.testScheduler.advanceUntilIdle()
        assertTrue(profiles.images.value.isEmpty())

        releaseStorage()
        scope.testScheduler.advanceUntilIdle()

        assertEquals(mapOf("a" to photos[1]), profiles.images.value)
    }

    @Test
    fun aCachedPhotoIsNotRestoredWhenTheTeamHidesPhotos() {
        holdStorage()
        val profiles = profiles(cacheWithTwoPhotos(), storage = heldStorage)
        profiles.update(mapOf("a" to TeamMember("a", "Ann", photoUrl = "https://photos/pa", photoId = "pa")),
            showsPhotos = false)
        releaseStorage()
        scope.testScheduler.advanceUntilIdle()

        assertTrue(profiles.images.value.isEmpty())
    }

    @Test
    fun aCallerCancelledBeforeTheLoadDoesNotLetAWriteEraseTheCachedPhotos() {
        holdStorage()
        val caller = CoroutineScope(dispatcher + Job())
        val profiles = profiles(cacheWithTwoPhotos(), scope = caller, storage = heldStorage)
        // The load is queued behind the held block when the chat that owns the profiles goes away.
        caller.cancel()
        profiles.update(mapOf(
            "a" to TeamMember("a", "Ann", photoId = "pa"),
            "b" to TeamMember("b", "Ben", photoId = "pb"),
        ))
        releaseStorage()

        val stored = TeamProfileCache(context, "team-test").load()
        assertArrayEquals(byteArrayOf(1), stored["a"]?.data)
        assertArrayEquals(byteArrayOf(2), stored["b"]?.data)
    }
}
