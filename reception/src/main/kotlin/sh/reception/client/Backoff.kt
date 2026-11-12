package sh.reception.client

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import sh.reception.Log
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

internal object Backoff {
    /** Milliseconds before attempt [attempt] + 1: 2 s doubling to 60 s, with ±25 % jitter. */
    fun delay(attempt: Int): Long {
        val seconds = min(2 * 2.0.pow((attempt - 1).coerceIn(0, 6)), 60.0) * Random.nextDouble(0.75, 1.25)
        return (seconds * 1000).toLong()
    }
}

/**
 * Retained across task cancellation and view changes; only foreground or explicit user actions reset it. [now] is its
 * clock, epoch milliseconds; a test passes the main looper's, which its scheduled waits run on.
 */
internal class RetryState(private val now: () -> Long = System::currentTimeMillis) {
    var attempts: Int = 0
        private set
    var stopped: Boolean = false
        private set
    private var transient = false

    /** Milliseconds of the next attempt on [now]'s clock. */
    var nextAttempt: Long? = null
        private set

    fun failed(error: Throwable) {
        if (error is ReceptionAPIError && error.isCooldown) {
            // A known cooldown kept the request off the network: wait for it without spending an attempt.
            nextAttempt = now() + (error.retryAfter ?: 0) * 1000L
            return
        }
        attempts += 1
        transient = ReceptionAPIError.isTransient(error)
        if (!transient) {
            stopped = true
            nextAttempt = null
            return
        }
        if (attempts >= 8) {
            stopped = true
            nextAttempt = null
            Log.debug { "Retries paused after $attempts attempts until the app becomes active" }
            return
        }
        val delay = (error as? ReceptionAPIError)?.retryAfter?.let { it * 1000L } ?: Backoff.delay(attempts)
        nextAttempt = now() + delay
        Log.debug { "Retry scheduled in ${String.format(Locale.ROOT, "%.2f", delay / 1000.0)}s, attempt ${attempts + 1}" }
    }

    /** Milliseconds until the next attempt, or null when no automatic attempt follows. */
    val delay: Long?
        get() {
            val next = nextAttempt
            if (stopped || next == null) return null
            return (next - now()).coerceAtLeast(0)
        }

    fun foreground() {
        // Non-retryable failures stay stopped. The foreground renews the budget for transient work.
        if (!transient) return
        attempts = 0
        stopped = false
        transient = false
        nextAttempt = null
    }
}

/** Lets one user action through a halted session. */
internal class RequestProbe {
    var available: Boolean = true
}

/** The iOS task-locals of a request, carried as a coroutine context element. */
internal data class RequestContext(
    val probe: RequestProbe? = null,
    /** Set while an automation event is sent: it may register the device before the user has written. */
    val automation: Boolean = false,
    /** Set while the app launches or returns to the foreground: an unused device registers so the team can reach it. */
    val launch: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestContext> {
        suspend fun current(): RequestContext = currentCoroutineContext()[Key] ?: RequestContext()
    }
}

/**
 * Awaits work several callers share. When that work is cancelled (the session ended) while this caller is still
 * running, the caller gets a `cancelled` error instead of a cancellation that would end it silently.
 */
internal suspend fun <T> Deferred<T>.awaitShared(): T = try {
    await()
} catch (cancellation: CancellationException) {
    if (!currentCoroutineContext().isActive) throw cancellation
    throw ReceptionAPIError("cancelled", 0)
}
