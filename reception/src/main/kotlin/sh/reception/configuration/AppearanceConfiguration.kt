package sh.reception.configuration

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.reception.Log
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionState
import sh.reception.client.ReceptionAPI
import sh.reception.client.ReceptionAPIError
import sh.reception.client.RetryState
import sh.reception.client.appearance
import kotlin.coroutines.cancellation.CancellationException

/**
 * The published appearance of the configured App ID. A fetch saves the latest compatible snapshot; opening the chat
 * activates it, so a visible chat is never restyled. Failures keep the last snapshot and never touch the session.
 */
internal class AppearanceConfiguration(private val now: () -> Long = System::currentTimeMillis) {
    var active: RemoteAppearance? by mutableStateOf(null)
        private set
    var activePushPrompt: PushPrompt? by mutableStateOf(null)
        private set
    private var latest: AppearanceEnvelope? = null
    private var api: ReceptionAPI? = null
    private var cache: AppearanceCache? = null
    private var etag: String? = null
    private var lastAttempt: Long? = null
    private var generation = 0
    private var task: Job? = null
    private var retry = RetryState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun configure(context: Context, api: ReceptionAPI, scope: String) {
        task?.cancel()
        task = null
        generation += 1
        this.api = api
        val cache = AppearanceCache(context, scope).also { this.cache = it }
        val snapshot = cache.load()
        latest = snapshot?.envelope
        activate()
        etag = snapshot?.etag
        lastAttempt = null
        retry = RetryState()
    }

    fun resumed() {
        retry = RetryState()
        lastAttempt = null
    }

    fun becameActive() {
        if (retry.stopped && retry.attempts >= 8) lastAttempt = null
        retry.foreground()
    }

    /** Called when the chat opens. */
    fun activate() {
        active = latest?.appearance
        activePushPrompt = PushPrompt.from(latest?.pushPrompt)
    }

    suspend fun refreshWhileActive() {
        while (currentCoroutineContext().isActive) {
            if (!ReceptionAppearance.remoteEnabled || ReceptionState.session?.halt != null || retry.stopped) return
            refresh()
            if (retry.stopped || ReceptionState.session?.halt != null) return
            delay(retry.delay ?: INTERVAL_MILLIS)
        }
    }

    /** At most one request at a time and one per five minutes, sooner only when a retry is due. */
    suspend fun refresh() {
        task?.let { return it.join() }
        if (ReceptionState.session?.halt != null || retry.stopped) return
        val api = ReceptionState.session?.api ?: api ?: return
        val time = now()
        val next = retry.nextAttempt
        val last = lastAttempt
        if (next != null) {
            if (time < next) return
        } else if (last != null && time - last < INTERVAL_MILLIS) {
            return
        }
        lastAttempt = time
        val generation = generation
        val etag = etag
        lateinit var task: Job
        task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                fetch(api, etag, generation)
            } finally {
                if (this@AppearanceConfiguration.task === task) this@AppearanceConfiguration.task = null
            }
        }
        this.task = task
        task.join()
    }

    private suspend fun fetch(api: ReceptionAPI, etag: String?, generation: Int) {
        try {
            val reply = api.appearance(etag)
            if (this.generation != generation) return
            val envelope = reply.envelope
            if (envelope != null) {
                if (!envelope.isCompatible) {
                    Log.error { "Remote appearance fetch failed, HTTP 200 invalid_appearance" }
                    return
                }
                latest = envelope
                reply.body?.let { cache?.save(it, reply.etag) }
                Log.debug { "Remote appearance refreshed, revision ${envelope.revision}" }
            } else {
                Log.debug { "Remote appearance unchanged" }
            }
            retry = RetryState()
            this.etag = reply.etag ?: etag
            // The visible chat stays as it is; the next opening activates this validated snapshot.
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ReceptionAPIError) {
            if (this.generation != generation) return
            if (error.code != "cancelled" && error.code != "session_halted") {
                Log.error { "Remote appearance fetch failed, HTTP ${error.status} ${error.code}" }
            }
            api.session?.recordFailure(error)
            if (api.session?.halt == null) retry.failed(error)
        }
    }

    private companion object {
        const val INTERVAL_MILLIS = 300_000L
    }
}
