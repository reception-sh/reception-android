package sh.reception.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import sh.reception.Log
import sh.reception.model.ConversationResponse

/**
 * Waits the service imposed on this device session, per limit scope. They are stored with the session, so neither
 * foreground, reopening the chat, an explicit retry nor a relaunch skips one. Only a current server check or
 * a session reset can release them early.
 */
internal class ServerCooldowns(
    private val store: DeviceStore,
    val now: () -> Long,
    private val scope: CoroutineScope,
) {
    @Serializable
    data class Cooldown(
        val scope: RetryScope,
        /** Epoch milliseconds. */
        val until: Long,
        /**
         * The service asked for at most a minute. Only such waits end in an automatic resend; the original length
         * decides, so a long wait that is nearly over still needs a manual retry.
         */
        val automatic: Boolean,
    )

    var active: Map<RetryScope, Cooldown> = emptyMap()
        private set

    /** Called when a wait starts or ends, so connection state and waiting resends can follow. */
    var onChange: (() -> Unit)? = null
    private val revisions = mutableMapOf<RetryScope, Int>()
    private var expiry: Job? = null

    /** Loads the stored waits once the store has opened. */
    fun restore() {
        // A clock set back must not stretch a stored wait beyond the one day the service can ask for.
        val latest = now() + ReceptionAPI.MAXIMUM_RETRY_AFTER * 1000L
        active = store.cooldowns.filter { it.until > now() }
            .associate { it.scope to it.copy(until = minOf(it.until, latest)) }
        store.cooldowns = active.values.toList()
        scheduleExpiry()
    }

    /** The latest unfinished cooldown among [scopes]. It resends automatically only when all of them allow it. */
    fun blocking(scopes: List<RetryScope>): Cooldown? {
        val now = now()
        val limit = now + ReceptionAPI.MAXIMUM_RETRY_AFTER * 1000L
        if (active.values.any { it.until > limit }) {
            active = active.mapValues { it.value.copy(until = minOf(it.value.until, limit)) }
            store.cooldowns = active.values.toList()
            scheduleExpiry()
        }
        val waits = scopes.toSet().mapNotNull { active[it] }.filter { it.until > now }
        val latest = waits.maxByOrNull { it.until } ?: return null
        return latest.copy(automatic = waits.all { it.automatic })
    }

    fun impose(scope: RetryScope, seconds: Int) {
        // Even a repeated rejection supersedes an in-flight read of the budgets.
        revisions[scope] = (revisions[scope] ?: 0) + 1
        val until = now() + seconds * 1000L
        // Retry-After has whole seconds: a report within a second of the known deadline describes the same wait.
        if (until <= (active[scope]?.until ?: Long.MIN_VALUE) + 1000) return
        active = active + (scope to Cooldown(scope, until, seconds <= AUTOMATIC_LIMIT))
        store.cooldowns = active.values.toList()
        Log.info { "Rate limited, ${scope.raw} paused for ${seconds}s" }
        scheduleExpiry()
        onChange?.invoke()
    }

    /** Attached only to an opening/foreground chat load. Failures also spend this local check interval. */
    fun beginRecheck(): Map<RetryScope, Int> {
        val now = now()
        val held = listOf(RetryScope.MESSAGES, RetryScope.UPLOADS).filter { scope ->
            val wait = blocking(listOf(scope)) ?: return@filter false
            !wait.automatic && wait.until - now > 60_000
        }
        if (held.isEmpty()) return emptyMap()
        val last = store.lastLimitCheck
        if (last != null) {
            if (last > now) {
                store.lastLimitCheck = now
                return emptyMap()
            }
            if (now - last < 60_000) return emptyMap()
        }
        store.lastLimitCheck = now
        return held.associateWith { revisions[it] ?: 0 }
    }

    /** Only explicit, current server answers can shorten a stored wait. They never turn it into a timer resend. */
    fun applyRecheck(limits: ConversationResponse.Limits?, checked: Map<RetryScope, Int>) {
        for ((scope, revision) in checked) {
            if ((revisions[scope] ?: 0) != revision) continue
            val seconds = limits?.seconds(scope) ?: continue
            val wait = active[scope] ?: continue
            if (wait.until <= now()) continue
            revisions[scope] = revision + 1
            active = if (seconds == 0) active - scope
            else active + (scope to Cooldown(scope, now() + seconds * 1000L, automatic = false))
        }
        store.cooldowns = active.values.toList()
        scheduleExpiry()
    }

    fun invalidate() {
        expiry?.cancel()
        expiry = null
        onChange = null
    }

    /** One timer for the nearest deadline publishes the end of a wait; nothing polls the service. */
    private fun scheduleExpiry() {
        expiry?.cancel()
        expiry = null
        val now = now()
        if (active.values.any { it.until <= now }) {
            active = active.filterValues { it.until > now }
            store.cooldowns = active.values.toList()
        }
        val next = active.values.minOfOrNull { it.until } ?: return
        expiry = scope.launch {
            delay(next - now)
            expiry = null
            scheduleExpiry()
            onChange?.invoke()
        }
    }

    companion object {
        const val AUTOMATIC_LIMIT: Int = 60
    }
}
