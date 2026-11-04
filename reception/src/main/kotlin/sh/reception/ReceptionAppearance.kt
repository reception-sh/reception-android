package sh.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.ReceptionAPIError
import sh.reception.configuration.AppearanceConfiguration
import sh.reception.configuration.ResolvedAppearance
import sh.reception.configuration.applying

/** The app's appearance, the dashboard's remote appearance and when it is fetched. Main thread only. */
internal object ReceptionAppearance {
    var appearance: Reception.Appearance by mutableStateOf(Reception.Appearance())
    private var remoteSetting by mutableStateOf(true)

    /** `Reception.usesRemoteAppearance`; false neither fetches nor applies remote settings and keeps the cache. */
    var remoteEnabled: Boolean
        get() = remoteSetting
        set(value) {
            if (remoteSetting == value) return
            remoteSetting = value
            updateRefresh()
        }
    val configuration: AppearanceConfiguration = AppearanceConfiguration()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshTask: Job? = null
    private val uuidPattern = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    /** Native defaults, then the app's [appearance], then the dashboard settings activated when the chat opened. */
    val resolved: ResolvedAppearance
        get() {
            val remote = if (remoteEnabled) configuration.active else null
            return remote?.applying(appearance) ?: ResolvedAppearance.local(appearance)
        }

    /** An uploaded appearance image on this service. */
    fun imageUrl(id: String): String? {
        val path = imagePath(id) ?: return null
        val api = ReceptionState.session?.api ?: return null
        return try {
            api.httpRequest(path, token = null).build().url.toString()
        } catch (_: ReceptionAPIError) {
            null
        }
    }

    /** IDs must be UUIDs, so remote values cannot choose other paths. */
    fun imagePath(id: String): String? = if (uuidPattern.matches(id)) "appearance/images/${id.lowercase()}" else null

    fun becameActive() {
        configuration.becameActive()
        updateRefresh()
    }

    fun enteredBackground() {
        refreshTask?.cancel()
        refreshTask = null
    }

    fun sessionResumed() {
        configuration.resumed()
        updateRefresh()
    }

    /** Fetches while the app is active and remote appearance is on; the configuration spaces the requests. */
    fun updateRefresh() {
        refreshTask?.cancel()
        refreshTask = null
        if (ReceptionState.session == null || !ApplicationLifecycle.isActive || !remoteEnabled) return
        refreshTask = scope.launch { configuration.refreshWhileActive() }
    }
}
