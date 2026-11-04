package sh.reception.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one thread for the SDK's disk work: preference commits, secure storage, the Keystore and cache files. Work runs
 * one block at a time in submission order, so a write submitted before a read is always visible to it. The main thread
 * never waits for it; results come back to the main thread, which checks that they still belong to the current session.
 */
internal object Storage {
    val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }

    /** Starts [block] on the storage thread once [after] has finished; independent of any session's lifetime. */
    fun launch(after: Job? = null, block: () -> Unit): Job = scope.launch {
        after?.join()
        block()
    }
}
