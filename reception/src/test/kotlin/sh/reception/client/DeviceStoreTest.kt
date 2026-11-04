package sh.reception.client

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeviceStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val credentials = mutableMapOf<String, String>()
    private val storage = DeviceStore.CredentialStorage(
        read = { account -> credentials[account]?.let { SecureStore.ReadResult.Value(it) } ?: SecureStore.ReadResult.Missing },
        save = { value, account -> credentials[account] = value; true },
        delete = { account -> credentials.remove(account); true },
    )

    private fun store(appId: String) = DeviceStore(context, appId, storage).apply { open() }

    @Test
    fun keepsTheGenerationUntilItRotates() {
        val first = DeviceStore.generation(context, "app_a")
        assertEquals(first, DeviceStore.generation(context, "app_a"))
        val rotated = DeviceStore.generation(context, "app_a", rotate = true)
        assertNotEquals(first, rotated)
        assertEquals(rotated, DeviceStore.generation(context, "app_a"))
        assertNotEquals(DeviceStore.generation(context, "app_b"), rotated)
    }

    @Test
    fun aRotatedGenerationStartsWithoutTheOldSession() {
        val old = store("app_rotation")
        old.save(SessionCredentials("secret", "user-1"))
        old.hasConversation = true
        old.draftText = "draft"
        old.cursor = 42
        old.pushPromptDeclined = true
        old.pending = DeviceIdentity(name = "Ada", pushToken = "token", pushEnvironment = "PRODUCTION")
        assertEquals(SessionCredentials("secret", "user-1"), old.loadCredentials())

        old.reset()
        val fresh = store("app_rotation")
        assertNull(fresh.loadCredentials())
        assertTrue(fresh.credentialsMissing)
        assertFalse(fresh.hasConversation)
        assertEquals("", fresh.draftText)
        assertEquals(0L, fresh.cursor)
        assertEquals(DeviceIdentity(), fresh.pending)
        // The notification card answer belongs to the device and App ID, not the session.
        assertTrue(fresh.pushPromptDeclined)
        // A cleared store refuses late draft writes from the discarded chat.
        old.draftText = "late"
        assertEquals("", old.draftText)
    }

    @Test
    fun twoServiceAddressesWithTheSameAppIdKeepSeparateSessions() {
        val appId = "app_addressAddressAddress1"
        val hosted = DeviceStore.namespace(appId, ReceptionAPI.defaultServiceUrl)
        val local = DeviceStore.namespace(appId, "http://10.0.2.2:3123/".toHttpUrl())
        // The production path keeps its namespace, so stored sessions survive this change.
        assertEquals(appId, hosted)
        assertEquals(appId, DeviceStore.namespace(appId, "https://reception.sh".toHttpUrl()))
        assertEquals(local, DeviceStore.namespace(appId, "http://10.0.2.2:3123".toHttpUrl()))

        DeviceStore(context, hosted, storage).apply { open() }.save(SessionCredentials("hosted"))
        val localStore = DeviceStore(context, local, storage).apply { open() }
        assertNull(localStore.loadCredentials())
        localStore.save(SessionCredentials("local"))
        localStore.cooldowns = listOf(ServerCooldowns.Cooldown(RetryScope.MESSAGES, Long.MAX_VALUE, automatic = false))

        val reopened = DeviceStore(context, hosted, storage).apply { open() }
        assertEquals(SessionCredentials("hosted"), reopened.loadCredentials())
        assertEquals(emptyList<ServerCooldowns.Cooldown>(), reopened.cooldowns)
        assertEquals(SessionCredentials("local"), DeviceStore(context, local, storage).apply { open() }.loadCredentials())
    }

    @Test
    fun anUnreadableSessionIsNotAMissingOne() {
        val unavailable = DeviceStore(context, "app_unavailable", credentialStorage = DeviceStore.CredentialStorage(
            read = { SecureStore.ReadResult.Unavailable }, save = { _, _ -> false }, delete = { true })).apply { open() }
        val error = runCatching { unavailable.loadCredentials() }.exceptionOrNull() as ReceptionAPIError
        assertEquals("secure_storage_unavailable", error.code)
        assertFalse(unavailable.credentialsMissing)
    }

    @Test
    fun resetChatKeepsTheSessionAndStoresTheRevision() {
        val store = store("app_reset")
        store.save(SessionCredentials("secret"))
        store.hasStartedChat = true
        store.cooldowns = listOf(ServerCooldowns.Cooldown(RetryScope.MESSAGES, Long.MAX_VALUE, automatic = false))
        store.resetChat(revision = 3)
        assertEquals(3, store.chatRevision)
        assertFalse(store.hasStartedChat)
        assertEquals(SessionCredentials("secret"), store.loadCredentials())
        // A reset by the team keeps the server's waits.
        assertEquals(1, store.cooldowns.size)
    }
}
