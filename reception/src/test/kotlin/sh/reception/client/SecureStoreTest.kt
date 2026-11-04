package sh.reception.client

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertArrayEquals
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
class SecureStoreTest {
    private val preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("secure-store-test", Context.MODE_PRIVATE)

    @Test
    fun aKeystoreFailureIsUnavailableNeverMissing() {
        // Robolectric has no AndroidKeyStore provider, so every Keystore call fails like a broken Keystore.
        val store = SecureStore(preferences, SecureStore.KeystoreKey)
        assertEquals(SecureStore.ReadResult.Missing, store.read("session"))
        assertFalse(store.save("secret", "session"))
        preferences.edit().putString("session", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
        assertEquals(SecureStore.ReadResult.Unavailable, store.read("session"))
    }

    @Test
    fun roundTripsThroughAesGcm() {
        val store = SecureStore(preferences, SoftwareKey())
        assertTrue(store.save("""{"sessionSecret":"s3cret"}""", "session"))
        assertEquals(SecureStore.ReadResult.Value("""{"sessionSecret":"s3cret"}"""), store.read("session"))
        assertFalse(preferences.getString("session", null).orEmpty().contains("s3cret"))
        assertTrue(store.delete("session"))
        assertEquals(SecureStore.ReadResult.Missing, store.read("session"))
    }

    @Test
    fun aWriteThatDoesNotReachTheDiskReportsFailure() {
        val store = SecureStore(FailingCommits(preferences), SoftwareKey())
        assertFalse(store.save("secret", "unsaved"))
        assertFalse(store.delete("unsaved"))
    }

    /** Preferences whose commits fail, like a full disk. */
    private class FailingCommits(private val real: SharedPreferences) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by real.edit() {
            override fun commit(): Boolean = false
        }
    }

    @Test
    fun anEntryAnotherKeySealedCountsAsMissing() {
        SecureStore(preferences, SoftwareKey()).save("value", "restored")
        // A backup restored onto another device brings the entry but not the Keystore key.
        val otherDevice = SoftwareKey().apply { create() }
        assertEquals(SecureStore.ReadResult.Missing, SecureStore(preferences, otherDevice).read("restored"))
        assertEquals(SecureStore.ReadResult.Missing, SecureStore(preferences, SoftwareKey()).read("restored"))
        preferences.edit().putString("restored", "not base64 !").commit()
        assertEquals(SecureStore.ReadResult.Missing, SecureStore(preferences, otherDevice).read("restored"))
    }

    @Test
    fun sealsWithAFreshTwelveByteIvAndDetectsTampering() {
        val box = SecretBox(SoftwareKey().create())
        val first = box.seal("same".toByteArray())
        val second = box.seal("same".toByteArray())
        assertEquals(12 + 4 + 16, first.size)
        assertNotEquals(first.take(12), second.take(12))
        assertArrayEquals("same".toByteArray(), box.open(first))
        first[first.size - 1] = (first[first.size - 1] + 1).toByte()
        assertNull(box.open(first))
        assertNull(box.open(ByteArray(20)))
    }
}
