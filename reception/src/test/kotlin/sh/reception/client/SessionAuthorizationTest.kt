package sh.reception.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.reception.ReceptionState
import sh.reception.model.EmptyResponse
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Token renewal, the `401` rules and the registration payload, against a local HTTP server. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionAuthorizationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val dispatcher = StandardTestDispatcher()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    // Read on MockWebServer's thread.
    @Volatile
    private var respond: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private val credentials = Collections.synchronizedMap(mutableMapOf<String, String>())
    @Volatile private var savesFail = false
    private val storage = DeviceStore.CredentialStorage(
        read = { account -> credentials[account]?.let { SecureStore.ReadResult.Value(it) } ?: SecureStore.ReadResult.Missing },
        save = { value, account -> if (savesFail) false else { credentials[account] = value; true } },
        delete = { account -> credentials.remove(account); true },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SecureStore.keys = SoftwareKey()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return respond(request)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        ReceptionState.resetForTests()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun error(status: Int, code: String) = json(status, """{"error":{"code":"$code","message":"m"}}""")

    private fun registeredSession(accessToken: String? = null): DeviceSession {
        val store = DeviceStore(context, "app_test${System.nanoTime()}", credentialStorage = storage).apply { open() }
        store.save(SessionCredentials("secret"))
        val session = DeviceSession(ReceptionAPI(APP_ID, server.url("/")), store, refreshSleep = {})
        session.accessToken = accessToken
        return session
    }

    private fun summary() = requests.map { "${it.method} ${it.path} ${it.getHeader("Authorization").orEmpty()}".trim() }

    /** The session's own requests (devices, conversation), without what configure reads besides them. */
    private fun sessionSummary() = requests.toList()
        .filter { it.path?.startsWith("/v1/devices") == true || it.path?.startsWith("/v1/conversation") == true }
        .map { "${it.method} ${it.path}" }

    @Test
    fun concurrentCallersShareOneRenewal() = runTest(dispatcher) {
        respond = { request ->
            when (request.path) {
                "/v1/devices/me/token" -> json(200, """{"token":"fresh"}""").setBodyDelay(200, TimeUnit.MILLISECONDS)
                else -> json(200, "{}")
            }
        }
        val session = registeredSession()
        listOf(
            async { session.api.request<EmptyResponse>("conversation/unread", authenticated = true) },
            async { session.api.request<EmptyResponse>("conversation/unread", authenticated = true) },
        ).awaitAll()
        assertEquals(1, requests.count { it.path == "/v1/devices/me/token" })
        assertEquals(listOf("Bearer fresh", "Bearer fresh"),
            requests.filter { it.path == "/v1/conversation/unread" }.map { it.getHeader("Authorization") })
        assertEquals("""{"sessionSecret":"secret"}""", requests.first { it.path == "/v1/devices/me/token" }.body.readUtf8())
    }

    @Test
    fun aCancelledCallerLeavesTheRenewalToTheNextCaller() = runTest(dispatcher) {
        val issued = AtomicInteger()
        val answer = CountDownLatch(1)
        respond = {
            answer.await(5, TimeUnit.SECONDS)
            json(200, """{"token":"token-${issued.incrementAndGet()}"}""")
        }
        val session = registeredSession()
        val first = async { session.renewedToken(replacing = null) }
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        // Backgrounding cancels an unread refresh while its renewal is in flight.
        first.cancelAndJoin()
        val second = async { session.renewedToken(replacing = null) }
        runCurrent()
        // The server answers only once the second caller waits for the same renewal.
        assertTrue(second.isActive && session.refresh != null)
        answer.countDown()
        assertEquals("token-1", second.await())
        assertEquals(1, requests.count { it.path == "/v1/devices/me/token" })
        assertEquals("token-1", session.accessToken)
        assertNull(session.refresh)
    }

    @Test
    fun aCancelledCallerLeavesTheRegistrationToTheNextCaller() = runTest(dispatcher) {
        val issued = AtomicInteger()
        val answer = CountDownLatch(1)
        respond = {
            answer.await(5, TimeUnit.SECONDS)
            val number = issued.incrementAndGet()
            json(201, """{"token":"token-$number","sessionSecret":"secret-$number"}""")
        }
        val store = DeviceStore(context, "app_test${System.nanoTime()}", credentialStorage = storage)
        val session = DeviceSession(ReceptionAPI(APP_ID, server.url("/")), store, refreshSleep = {})
        val first = async { withContext(RequestContext(automation = true)) { session.token() } }
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        first.cancelAndJoin()
        val second = async { withContext(RequestContext(automation = true)) { session.token() } }
        runCurrent()
        // The server answers only once the second caller waits for the same registration.
        assertTrue(second.isActive && session.registration != null)
        answer.countDown()
        assertEquals("token-1", second.await())
        assertEquals(1, requests.count { it.path == "/v1/devices" })
        assertEquals(SessionCredentials("secret-1"), session.storedCredentials())
        assertNull(session.registration)
    }

    @Test
    fun callersThatReadMissingCredentialsAfterAnUnreadableStoreRegisterOnce() = runTest(dispatcher) {
        respond = { json(201, """{"token":"t","sessionSecret":"s"}""") }
        // Every read waits for the test's answer; the storage thread runs them one at a time in order.
        val answers = LinkedBlockingQueue<SecureStore.ReadResult>()
        val reads = Semaphore(0)
        val gated = DeviceStore.CredentialStorage(
            read = { reads.release(); answers.take() },
            save = storage.save,
            delete = storage.delete,
        )
        val store = DeviceStore(context, "app_test${System.nanoTime()}", credentialStorage = gated)
        val session = DeviceSession(ReceptionAPI(APP_ID, server.url("/")), store, refreshSleep = {})
        answers.put(SecureStore.ReadResult.Unavailable)
        session.prepared()
        assertTrue(session.credentialsUnreadable)
        val callers = List(2) { async { withContext(RequestContext(automation = true)) { session.token() } } }
        runCurrent()
        // The first reads of both callers fail again, so both go on to read once more before registering.
        answers.put(SecureStore.ReadResult.Unavailable)
        answers.put(SecureStore.ReadResult.Unavailable)
        // Both second reads are queued: the first one is on the storage thread, the other caller has caught up.
        withContext(Dispatchers.IO) { assertTrue(reads.tryAcquire(4, 5, TimeUnit.SECONDS)) }
        runCurrent()
        answers.put(SecureStore.ReadResult.Missing)
        answers.put(SecureStore.ReadResult.Missing)
        assertEquals(listOf("t", "t"), callers.awaitAll())
        assertEquals(1, requests.count { it.path == "/v1/devices" })
    }

    @Test
    fun aCallerThatReadsDuringAnUnsavedRegistrationSendsNothing() = runTest(dispatcher) {
        respond = { json(201, """{"token":"t","sessionSecret":"s"}""") }
        val answers = LinkedBlockingQueue<SecureStore.ReadResult>()
        val reads = Semaphore(0)
        val gated = DeviceStore.CredentialStorage(
            read = { reads.release(); answers.take() },
            save = storage.save,
            delete = storage.delete,
        )
        val store = DeviceStore(context, "app_test${System.nanoTime()}", credentialStorage = gated)
        val session = DeviceSession(ReceptionAPI(APP_ID, server.url("/")), store, refreshSleep = {})
        answers.put(SecureStore.ReadResult.Unavailable)
        session.prepared()
        savesFail = true
        val callers = List(2) {
            async {
                try {
                    withContext(RequestContext(automation = true)) {
                        session.api.request<EmptyResponse>("conversation/unread", authenticated = true)
                    }
                    null
                } catch (error: ReceptionAPIError) {
                    error.code
                }
            }
        }
        runCurrent()
        answers.put(SecureStore.ReadResult.Unavailable)
        answers.put(SecureStore.ReadResult.Unavailable)
        // The first caller's second read is on the storage thread, the second caller's is queued behind it.
        withContext(Dispatchers.IO) { assertTrue(reads.tryAcquire(4, 5, TimeUnit.SECONDS)) }
        runCurrent()
        answers.put(SecureStore.ReadResult.Missing)
        // The first caller registers; its save waits on the storage thread behind the second caller's read.
        withContext(Dispatchers.IO) { assertTrue(reads.tryAcquire(1, 5, TimeUnit.SECONDS)) }
        while (session.accessToken == null) {
            withContext(Dispatchers.IO) { Thread.sleep(10) }
            runCurrent()
        }
        answers.put(SecureStore.ReadResult.Missing)
        assertEquals(listOf("secure_storage_unavailable", "secure_storage_unavailable"), callers.awaitAll())
        assertEquals(listOf("POST /v1/devices"), summary())
    }

    @Test
    fun aRegistrationThatCannotBeStoredIsRetriedFromMemoryBeforeUse() = runTest(dispatcher) {
        respond = { json(201, """{"token":"t","sessionSecret":"s"}""") }
        val store = DeviceStore(context, "app_test${System.nanoTime()}", credentialStorage = storage)
        val session = DeviceSession(ReceptionAPI(APP_ID, server.url("/")), store, refreshSleep = {})
        savesFail = true
        try {
            withContext(RequestContext(automation = true)) { session.token() }
            fail("expected secure_storage_unavailable")
        } catch (error: ReceptionAPIError) {
            assertEquals("secure_storage_unavailable", error.code)
        }
        assertTrue(session.credentialsUnsaved)
        try {
            withContext(RequestContext(automation = true)) { session.token() }
            fail("expected secure_storage_unavailable")
        } catch (error: ReceptionAPIError) {
            assertEquals("secure_storage_unavailable", error.code)
        }
        savesFail = false
        assertEquals("t", withContext(RequestContext(automation = true)) { session.token() })
        assertFalse(session.credentialsUnsaved)
        assertEquals(SessionCredentials("s"), Storage.run { store.loadCredentials() })
        assertEquals(1, requests.count { it.path == "/v1/devices" })
    }

    @Test
    fun aRenewalThatEndsWithUnsavedCredentialsHandsOutNoToken() = runTest(dispatcher) {
        val answer = CountDownLatch(1)
        respond = { request ->
            if (request.path == "/v1/devices/me/token") answer.await(5, TimeUnit.SECONDS)
            json(200, """{"token":"fresh"}""")
        }
        val session = registeredSession()
        session.storedCredentials()
        val callers = List(2) {
            async {
                try {
                    session.renewedToken(replacing = null)
                } catch (error: ReceptionAPIError) {
                    error.code
                }
            }
        }
        runCurrent()
        while (requests.none { it.path == "/v1/devices/me/token" }) {
            withContext(Dispatchers.IO) { Thread.sleep(10) }
            runCurrent()
        }
        // A device update stores a new verified user while the renewal waits for the network, and the save fails.
        savesFail = true
        session.storeVerifiedUserId("user")
        assertTrue(session.credentialsUnsaved)
        answer.countDown()
        assertEquals(listOf("secure_storage_unavailable", "secure_storage_unavailable"), callers.awaitAll())
        savesFail = false
        assertEquals("fresh", session.renewedToken(replacing = null))
        assertFalse(session.credentialsUnsaved)
    }

    @Test
    fun aTokenWaitsForCredentialsThatChangedDuringItsSave() = runTest(dispatcher) {
        val saves = AtomicInteger(0)
        val firstSave = CountDownLatch(1)
        val secondSave = CountDownLatch(1)
        val gated = DeviceStore.CredentialStorage(
            read = storage.read,
            save = { value, account ->
                when (saves.incrementAndGet()) {
                    1 -> firstSave.await(5, TimeUnit.SECONDS)
                    2 -> secondSave.await(5, TimeUnit.SECONDS)
                }
                storage.save(value, account)
            },
            delete = storage.delete,
        )
        val store = DeviceStore(context, "app_test${System.nanoTime()}", credentialStorage = gated).apply { open() }
        val session = DeviceSession(ReceptionAPI(APP_ID, server.url("/")), store, refreshSleep = {})
        session.prepared()
        session.credentials = SessionCredentials("secret")
        session.credentialsUnsaved = true
        session.accessToken = "t"
        val caller = async { session.token() }
        while (saves.get() < 1) {
            withContext(Dispatchers.IO) { Thread.sleep(10) }
            runCurrent()
        }
        // A device update replaces the verified user while the caller's save is on the storage thread.
        val update = async { session.storeVerifiedUserId("user") }
        runCurrent()
        firstSave.countDown()
        while (saves.get() < 2) {
            withContext(Dispatchers.IO) { Thread.sleep(10) }
            runCurrent()
        }
        withContext(Dispatchers.IO) { Thread.sleep(50) }
        runCurrent()
        assertFalse("token handed out before the newer credentials were saved", caller.isCompleted)
        secondSave.countDown()
        assertEquals("t", caller.await())
        update.await()
        assertFalse(session.credentialsUnsaved)
        assertEquals(SessionCredentials("secret", "user"), Storage.run { store.loadCredentials() })
    }

    @Test
    fun aLogoutDuringTheCredentialSaveHandsOutNoToken() = runTest(dispatcher) {
        val saving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = DeviceStore.CredentialStorage(
            read = storage.read,
            save = { value, account ->
                saving.countDown()
                release.await(5, TimeUnit.SECONDS)
                storage.save(value, account)
            },
            delete = storage.delete,
        )
        val store = DeviceStore(context, "app_test${System.nanoTime()}", credentialStorage = gated).apply { open() }
        val session = DeviceSession(ReceptionAPI(APP_ID, server.url("/")), store, refreshSleep = {})
        session.prepared()
        session.credentials = SessionCredentials("secret")
        session.credentialsUnsaved = true
        session.accessToken = "t"
        val caller = async { runCatching { session.token() } }
        while (saving.count > 0) {
            withContext(Dispatchers.IO) { Thread.sleep(10) }
            runCurrent()
        }
        // The logout ends the session while the caller's save is on the storage thread.
        session.deactivate()
        release.countDown()
        val error = caller.await().exceptionOrNull()
        assertEquals("token handed out after the logout", "cancelled", (error as? ReceptionAPIError)?.code)
    }

    @Test
    fun aRetryAfterTheRenewalIsAuthorizedAgain() = runTest(dispatcher) {
        val renewing = CountDownLatch(1)
        val release = CountDownLatch(1)
        respond = { request ->
            when {
                request.path == "/v1/devices/me/token" -> {
                    renewing.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    json(200, """{"token":"new"}""")
                }
                request.getHeader("Authorization") == "Bearer old" -> error(401, "unauthorized")
                else -> json(200, "{}")
            }
        }
        val session = registeredSession(accessToken = "old")
        val caller = async { runCatching { session.api.request<EmptyResponse>("conversation/unread", authenticated = true) } }
        while (renewing.count > 0) {
            withContext(Dispatchers.IO) { Thread.sleep(10) }
            runCurrent()
        }
        // Another request's answer blocked the device while the renewal was on the network.
        session.halt = DeviceSession.HaltReason.BLOCKED
        release.countDown()
        val error = caller.await().exceptionOrNull()
        assertEquals("session_halted", (error as? ReceptionAPIError)?.code)
        assertEquals(listOf("GET /v1/conversation/unread Bearer old", "POST /v1/devices/me/token"), summary())
    }

    @Test
    fun anUnauthorizedRequestIsRetriedOnceWithARenewedToken() = runTest(dispatcher) {
        respond = { request ->
            when {
                request.path == "/v1/devices/me/token" -> json(200, """{"token":"new"}""")
                request.getHeader("Authorization") == "Bearer old" -> error(401, "unauthorized")
                else -> json(200, "{}")
            }
        }
        val session = registeredSession(accessToken = "old")
        session.api.request<EmptyResponse>("conversation/unread", authenticated = true)
        assertEquals(listOf("GET /v1/conversation/unread Bearer old", "POST /v1/devices/me/token",
            "GET /v1/conversation/unread Bearer new"), summary())
    }

    @Test
    fun aSecondUnauthorizedAnswerIsNotRetriedAgain() = runTest(dispatcher) {
        respond = { request ->
            if (request.path == "/v1/devices/me/token") json(200, """{"token":"new"}""") else error(401, "unauthorized")
        }
        val session = registeredSession(accessToken = "old")
        try {
            session.api.request<EmptyResponse>("conversation/unread", authenticated = true)
            fail("expected unauthorized")
        } catch (error: ReceptionAPIError) {
            assertEquals(401, error.status)
            assertEquals("unauthorized", error.code)
        }
        assertEquals(3, requests.size)
        // An unauthorized answer never ends the session.
        assertEquals(SessionCredentials("secret"), session.storedCredentials())
    }

    @Test
    fun aRevokedSessionResetsLocallyAndKeepsTheStagedIdentity() = runTest(dispatcher) {
        respond = { request ->
            if (request.path == "/v1/devices/me/token") error(401, "session_revoked") else json(200, "{}")
        }
        ReceptionAPI.serviceUrl = server.url("/")
        ReceptionState.configure(context, "app_revokedRevokedRevoked1")
        val session = ReceptionState.session ?: error("not configured")
        session.prepared()
        Storage.run { session.store.save(SessionCredentials("secret")) }
        session.credentials = SessionCredentials("secret")
        session.store.hasConversation = true
        session.stage { DeviceIdentity(name = "Ada") }
        try {
            session.api.request<EmptyResponse>("conversation/unread", authenticated = true)
            fail("expected session_revoked")
        } catch (error: ReceptionAPIError) {
            assertEquals("session_revoked", error.code)
        }
        val current = ReceptionState.session ?: error("no session")
        assertNotSame(session, current)
        assertTrue(session.invalidated)
        assertFalse(current.isRegistered)
        assertFalse(current.store.hasConversation)
        assertEquals(DeviceIdentity(name = "Ada"), current.pending)
        current.prepared()
        assertEquals(DeviceIdentity(name = "Ada"), current.store.pending)
        // Configure's own reads (automations, appearance, paywalls) arrive in any order; only the session's count.
        assertEquals(listOf("POST /v1/devices/me/token"), sessionSummary())
    }

    @Test
    fun registersWithTheAndroidPayloadAndSendsThePushTokenWithTheUpdate() = runTest(dispatcher) {
        respond = { request ->
            when (request.path) {
                "/v1/devices" -> json(201, """{"token":"t","sessionSecret":"s","verifiedUserId":null}""")
                else -> json(200, """{"verifiedUserId":null,"push":false}""")
            }
        }
        ReceptionAPI.serviceUrl = server.url("/")
        ReceptionState.configure(context, "app_payloadPayloadPayload1")
        val session = ReceptionState.session ?: error("not configured")
        session.stage {
            DeviceIdentity(externalUserId = "user-1", metadata = mapOf("plan" to "pro"), pushToken = "fcm:token_1",
                pushEnvironment = "PRODUCTION")
        }
        withContext(RequestContext(automation = true)) { session.token() }
        session.update()

        // Configure's own reads arrive on their own connections in any order; each session request is found by path.
        assertEquals(listOf("POST /v1/devices", "PATCH /v1/devices/me"), sessionSummary())
        val registered = requests.single { it.method == "POST" && it.path == "/v1/devices" }
        val updated = requests.single { it.method == "PATCH" && it.path == "/v1/devices/me" }
        val registration = Json.parseToJsonElement(registered.body.readUtf8()).jsonObject
        assertEquals("app_payloadPayloadPayload1", registered.getHeader("X-App-Id"))
        assertEquals("Reception-Android/1.0.0", registered.getHeader("User-Agent"))
        assertEquals(JsonPrimitive("android"), registration["platform"])
        assertEquals(JsonPrimitive("1.0.0"), registration["sdkVersion"])
        assertEquals(JsonArray(listOf(JsonPrimitive("linkCards"), JsonPrimitive("screens"))), registration["capabilities"])
        assertEquals(JsonPrimitive("user-1"), registration["externalUserId"])
        assertEquals(JsonObject(mapOf("plan" to JsonPrimitive("pro"))), registration["metadata"])
        assertEquals(JsonPrimitive(true), registration["updatesAppBadge"])
        assertEquals(JsonArray(emptyList()), registration["paywalls"])
        assertEquals(JsonArray(emptyList()), registration["screens"])
        for (field in listOf("appVersion", "appBuild", "osVersion", "deviceModel", "locale", "timezone", "language", "bundleId")) {
            assertTrue("$field missing", (registration[field] as? JsonPrimitive)?.content?.isNotBlank() == true)
        }
        // Registration carries no push token; the device update that follows does.
        assertNull(registration["pushToken"])
        assertNull(registration["pushEnvironment"])

        val update = Json.parseToJsonElement(updated.body.readUtf8()).jsonObject
        assertEquals("Bearer t", updated.getHeader("Authorization"))
        assertEquals(JsonPrimitive("fcm:token_1"), update["pushToken"])
        assertEquals(JsonPrimitive("PRODUCTION"), update["pushEnvironment"])
        assertEquals(JsonPrimitive("android"), update["platform"])
        assertEquals(SessionCredentials("s"), session.storedCredentials())
        assertEquals(false, session.store.pushActive)
    }

    private companion object {
        const val APP_ID = "app_lQCXNRFINXc8FEnriGpad3"
    }
}
