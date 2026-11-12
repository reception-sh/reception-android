package sh.reception.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.savedstate.SavedStateRegistry
import sh.reception.Log
import java.util.UUID

/**
 * A lasting identity for each of the app's `ComponentActivity`s, kept in its `SavedStateRegistry` from the moment the
 * SDK first sees it (its creation, from `configure` on, or its first resume), so every state Android saves later carries
 * it and the instance Android recreates from that state is known as the one it replaces ([ReturningSheet]), however long
 * before a chat opened the state was saved. A state saved before the SDK saw the activity has none. The provider holds
 * only the identity string. Other activities have no registry and no identity. Main thread only.
 *
 * The registry belongs to the host: a provider under the key that is not the SDK's own is never run or removed, and a
 * registry that fails (a restored state that cannot be read) leaves that activity without an identity.
 */
internal object ActivityIdentity {
    /** The saved-state key; its value is the identity. */
    private const val KEY = "sh.reception.returningSheet"

    /** The SDK's own provider; [identity] is null for an activity whose restored identity could not be read. */
    private class Provider(val identity: String?) : SavedStateRegistry.SavedStateProvider {
        override fun saveState(): Bundle = identity?.let { bundleOf(KEY to it) } ?: Bundle()
    }

    /** Whether a failure was logged already; once per process is enough to tell why launcher returns may not work. */
    private var reported = false

    /** [activity]'s identity: the one it already has, the one its saved state restores, or a new one. */
    fun of(activity: Activity): String? {
        if (activity !is ComponentActivity) return null
        return guarded("Activity has no identity") { identity(activity.savedStateRegistry) }
    }

    private fun identity(registry: SavedStateRegistry): String? {
        when (val provider = registry.getSavedStateProvider(KEY)) {
            is Provider -> return provider.identity
            null -> Unit
            else -> return null.also { report("Activity has no identity, its saved-state key is taken") }
        }
        if (!registry.isRestored) return null
        // A restored identity that cannot be read is not replaced by a new one: this instance stays without.
        val identity = guarded("Activity has no identity") {
            registry.consumeRestoredStateForKey(KEY)?.getString(KEY) ?: UUID.randomUUID().toString()
        }
        registry.registerSavedStateProvider(KEY, Provider(identity))
        return identity
    }

    /** [activity] was destroyed: Android saved its state before, nothing more is saved. */
    fun forget(activity: Activity) {
        if (activity !is ComponentActivity) return
        val registry = activity.savedStateRegistry
        guarded("Activity identity not removed") {
            if (registry.getSavedStateProvider(KEY) is Provider) registry.unregisterSavedStateProvider(KEY)
        }
    }

    /**
     * The host boundary: a lifecycle callback of the host must never fail because of the identity, whatever its
     * registry or restored state throws.
     */
    private fun <T> guarded(failure: String, block: () -> T): T? = try {
        block()
    } catch (error: RuntimeException) {
        report("$failure, its saved state failed (${error.javaClass.simpleName})")
        null
    }

    private fun report(message: String) {
        if (reported) return
        reported = true
        Log.debug { message }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun resetForTests() {
        reported = false
    }
}
