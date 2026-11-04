package sh.reception.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import sh.reception.Log
import sh.reception.model.EmptyResponse
import kotlin.coroutines.cancellation.CancellationException

/**
 * Session revocations queued by `logout()`. The queue keeps the original App ID and session secret in secure storage,
 * retries transient failures with backoff while the app is in the foreground and resumes after a relaunch.
 */
internal class LogoutRevocations(private val secureStore: SecureStore) {
    @Serializable
    private data class Entry(val appId: String, val sessionSecret: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var entries = listOf<Entry>()
    private var loaded = false
    private var task: Job? = null
    private val retries = mutableMapOf<Entry, RetryState>()

    init {
        load()
    }

    /** A queue that cannot be read yet is merged later, never overwritten. */
    private fun load() {
        if (loaded) return
        when (val result = secureStore.read(ACCOUNT)) {
            SecureStore.ReadResult.Unavailable -> return
            SecureStore.ReadResult.Missing -> loaded = true
            is SecureStore.ReadResult.Value -> {
                val saved = try {
                    ReceptionAPI.json.decodeFromString(serializer, result.value)
                } catch (_: SerializationException) {
                    emptyList()
                } catch (_: IllegalArgumentException) {
                    emptyList()
                }
                entries = saved + entries.filter { it !in saved }
                loaded = true
            }
        }
        if (entries.isNotEmpty()) persist()
    }

    fun enqueue(api: ReceptionAPI, sessionSecret: String) {
        load()
        val entry = Entry(api.appId, sessionSecret)
        if (entry !in entries) {
            entries = entries + entry
            persist()
        }
        resume()
    }

    fun becameActive() {
        retries.values.forEach { it.foreground() }
        resume()
    }

    fun resumed(api: ReceptionAPI) {
        entries.filter { it.appId == api.appId }.forEach { retries.remove(it) }
        resume()
    }

    fun resume() {
        load()
        if (task != null || entries.isEmpty() || !ApplicationLifecycle.isActive) return
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                revokeQueued()
            } finally {
                if (task === job) task = null
            }
        }
        task = job
        job.start()
    }

    fun suspend() {
        task?.cancel()
        task = null
    }

    private suspend fun revokeQueued() {
        while (true) {
            val ready = entries.filter { retries[it]?.stopped != true }
            if (ready.isEmpty()) return
            for (entry in ready) {
                if ((retries[entry]?.nextAttempt ?: 0) > System.currentTimeMillis()) continue
                if (!revoke(entry)) continue
                entries = entries - entry
                retries.remove(entry)
                persist()
            }
            val next = entries.filter { retries[it]?.stopped != true }.mapNotNull { retries[it]?.nextAttempt }.minOrNull()
                ?: return
            delay((next - System.currentTimeMillis()).coerceAtLeast(0))
        }
    }

    /** True when the entry is finished: revoked, or failed in a way a retry cannot improve. */
    private suspend fun revoke(entry: Entry): Boolean {
        try {
            ReceptionAPI(entry.appId).request<EmptyResponse>("devices/me/logout", "POST",
                body = ReceptionAPI.encode(SecretInput(entry.sessionSecret)), expectedStatus = 200)
            Log.debug { "Session revocation completed" }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val failure = error as? ReceptionAPIError
            val description = "HTTP ${failure?.status ?: 0} ${failure?.code ?: "connection_failed"}"
            if (ReceptionAPIError.isTransient(error)) {
                Log.error { "Session revocation failed, $description, will retry" }
                retries.getOrPut(entry) { RetryState() }.failed(error)
                return false
            }
            // Logout answers 200 for unknown or revoked sessions, so other errors cannot improve.
            Log.error { "Session revocation dropped, $description" }
        }
        return true
    }

    @Serializable
    private class SecretInput(val sessionSecret: String)

    private fun persist() {
        if (!loaded) return
        // Retain in-memory work if secure storage is temporarily unavailable.
        secureStore.save(ReceptionAPI.json.encodeToString(serializer, entries), ACCOUNT)
    }

    private companion object {
        const val ACCOUNT = "pendingSessionRevocations"
        val serializer = ListSerializer(Entry.serializer())
    }
}
