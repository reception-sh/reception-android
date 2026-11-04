package sh.reception.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import sh.reception.Log
import sh.reception.model.EmptyResponse
import kotlin.coroutines.cancellation.CancellationException

/**
 * Session revocations queued by `logout()`. The queue keeps the original App ID, service address and session secret in secure storage,
 * retries transient failures with backoff while the app is in the foreground and resumes after a relaunch.
 */
internal class LogoutRevocations(private val secureStore: SecureStore) {
    /** [serviceUrl] is null for the hosted service and for entries queued before addresses were kept. */
    @Serializable
    private data class Entry(val appId: String, val sessionSecret: String, val serviceUrl: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var entries = listOf<Entry>()
    private var loaded = false
    private var task: Job? = null
    private val retries = mutableMapOf<Entry, RetryState>()

    private var loading: Job? = null

    init {
        load()
    }

    /** Reads the stored queue on the storage thread. A queue that cannot be read yet is merged later, never overwritten. */
    private fun load() {
        if (loaded || loading != null) return
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                when (val result = Storage.run { secureStore.read(ACCOUNT) }) {
                    SecureStore.ReadResult.Unavailable -> return@launch
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
            } finally {
                if (loading === job) loading = null
            }
            resume()
        }
        loading = job
        job.start()
    }

    /**
     * Queues the revocation of the session secret [sessionSecret] reads. The result is true once the queue, merged with
     * the stored one, is on disk; false when it is held in memory only (secure storage unreadable or a failed write) or
     * when there is nothing to revoke. Either way it finishes only after the initial load, so the caller's local reset
     * never deletes the credentials before the revocation had its chance to reach the disk.
     */
    fun enqueue(api: ReceptionAPI, sessionSecret: suspend () -> String?): Deferred<Boolean> = scope.async {
        val secret = try {
            sessionSecret()
        } catch (_: ReceptionAPIError) {
            Log.error { "Session revocation skipped, secure storage unavailable" }
            return@async false
        } ?: return@async false
        load()
        loading?.join()
        val entry = Entry(api.appId, secret, address(api.baseUrl))
        if (entry !in entries) entries = entries + entry
        val stored = persist()
        resume()
        stored
    }

    fun becameActive() {
        retries.values.forEach { it.foreground() }
        resume()
    }

    fun resumed(api: ReceptionAPI) {
        entries.filter { it.appId == api.appId && it.serviceUrl == address(api.baseUrl) }.forEach { retries.remove(it) }
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
            val serviceUrl = entry.serviceUrl?.toHttpUrlOrNull() ?: ReceptionAPI.defaultServiceUrl
            ReceptionAPI(entry.appId, serviceUrl).request<EmptyResponse>("devices/me/logout", "POST",
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

    /** True when the queue reached the disk. An unread queue is never written, so it cannot overwrite the stored one. */
    private suspend fun persist(): Boolean {
        if (!loaded) {
            Log.error { "Session revocation queue not stored, secure storage unreadable, kept in memory" }
            return false
        }
        val value = ReceptionAPI.json.encodeToString(serializer, entries)
        val stored = Storage.run { secureStore.save(value, ACCOUNT) }
        if (!stored) Log.error { "Session revocation queue not stored, kept in memory" }
        return stored
    }

    private companion object {
        const val ACCOUNT = "pendingSessionRevocations"

        /** The session's own service address, so a revocation reaches the service that issued the session. */
        fun address(serviceUrl: HttpUrl): String? = DeviceStore.normalized(serviceUrl)
            .takeIf { it != DeviceStore.normalized(ReceptionAPI.defaultServiceUrl) }
        val serializer = ListSerializer(Entry.serializer())
    }
}
