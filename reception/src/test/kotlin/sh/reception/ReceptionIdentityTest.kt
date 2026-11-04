package sh.reception

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.reception.client.SecureStore
import sh.reception.client.SoftwareKey

/** `identify` with one argument names the user; identity tokens go only through `identifyWithToken`. */
@RunWith(RobolectricTestRunner::class)
class ReceptionIdentityTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // The shared secure store keeps the key source it is created with; Robolectric has no Android Keystore.
        SecureStore.keys = SoftwareKey()
    }

    @After
    fun tearDown() {
        ReceptionState.resetForTests()
    }

    @Test
    fun identifyWithOneArgumentStagesTheUserId() {
        Reception.configure(context, "app_identifyIdentifyIdenti")
        // A JWT-shaped value is still a user ID here; this call compiles only against identify(userId, …).
        Reception.identify("eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyX2lkIjoieCJ9.c2lnbmF0dXJl")
        val session = requireNotNull(ReceptionState.session)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyX2lkIjoieCJ9.c2lnbmF0dXJl", session.pending.externalUserId)
        assertNull(session.pending.name)
        assertNull(ReceptionIdentity.token)
    }
}
