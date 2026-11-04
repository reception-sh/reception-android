package sh.reception.viewmodel

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import sh.reception.ReceptionState
import sh.reception.client.DeviceSession
import sh.reception.client.ReceptionAPI
import sh.reception.client.SecureStore
import sh.reception.client.SoftwareKey
import sh.reception.client.isRegistered
import java.util.Collections

/**
 * A configured, registered and active SDK against a local server, with the real main looper and storage thread.
 * [respond] answers each request; unknown paths get `{}`.
 */
internal class ChatHarness {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var respond: (RecordedRequest) -> MockResponse? = { null }

    fun start(): ChatModel {
        SecureStore.keys = SoftwareKey()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return respond(request) ?: default(request)
            }
        }
        server.start()
        ReceptionAPI.serviceUrl = server.url("/")
        ReceptionState.configure(RuntimeEnvironment.getApplication(), APP_ID)
        // Robolectric does not run the process lifecycle initializer; the app becomes active by hand.
        (ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
        val chat = ReceptionState.chatModel()
        mainLoopUntil { session.isRegistered && chat.isRestored }
        return chat
    }

    val session: DeviceSession
        get() = requireNotNull(ReceptionState.session)

    fun stop() {
        (ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED
        ReceptionState.resetForTests()
        server.shutdown()
    }

    fun json(body: String, status: Int = 200): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    fun error(status: Int, code: String): MockResponse = json("""{"error":{"code":"$code","message":"m"}}""", status)

    fun requests(method: String, path: String): List<RecordedRequest> =
        requests.toList().filter { it.method == method && it.path == path }

    /** Runs main-looper work until [condition] holds; network and storage finish on their own threads. */
    fun mainLoopUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out, requests: ${requests.toList().map { "${it.method} ${it.path}" }}", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun default(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
        "/v1/devices" -> json("""{"token":"t","sessionSecret":"s"}""", 201)
        "/v1/conversation/unread" -> json("""{"hasConversation":false,"unreadCount":0}""")
        else -> json("{}")
    }

    companion object {
        const val APP_ID = "app_chatChatChatChatChatCh"

        fun message(id: String, seq: Long, sender: String = "AGENT", clientId: String? = null, text: String = "t"): String =
            """{"id":"$id",${clientId?.let { "\"clientId\":\"$it\"," } ?: ""}"seq":$seq,"sender":"$sender","text":"$text",""" +
                """"attachments":[],"createdAt":"2026-11-04T12:00:0${seq % 10}.000Z"}"""

        const val CONVERSATION = """{"id":"c1","status":"OPEN","unreadForUser":0,"createdAt":"2026-11-04T12:00:00.000Z"}"""
    }
}
