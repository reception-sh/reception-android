package sh.reception.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import sh.reception.Log

/**
 * Whether the device has a default network, the Android counterpart of `NWPathMonitor`'s satisfied path. Reports the
 * current answer right after [start] and every change after it, on the main thread, until [stop].
 */
internal class NetworkMonitor(context: Context, private val changed: (Boolean) -> Unit) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { deliver(true) }
        }

        override fun onLost(network: Network) {
            main.post { deliver(false) }
        }
    }

    fun start() {
        val manager = manager ?: return
        running = true
        // No callback arrives while there is no network at all, so the current state is reported first.
        val connected = manager.activeNetwork != null
        main.post { deliver(connected) }
        registered = try {
            manager.registerDefaultNetworkCallback(callback)
            true
        } catch (_: RuntimeException) {
            // The system limits callbacks per app; without one the state stays as first reported.
            Log.debug { "Network monitoring unavailable" }
            false
        }
    }

    fun stop() {
        running = false
        if (!registered) return
        registered = false
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun deliver(satisfied: Boolean) {
        if (running) changed(satisfied)
    }
}
