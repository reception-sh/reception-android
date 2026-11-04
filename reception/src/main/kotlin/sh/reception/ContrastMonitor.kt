package sh.reception

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The system's contrast preference (Android 14+, `UiModeManager.getContrast()` above zero), observed while a chat is
 * visible. Each visible chat calls [start] and [stop] once; the listener stays registered while any is visible. Below
 * Android 14 the preference is unknown and stays false. Main thread only.
 */
internal object ContrastMonitor {
    private val mutableIncreasedContrast = MutableStateFlow(false)
    val increasedContrast: StateFlow<Boolean> = mutableIncreasedContrast.asStateFlow()
    private var visibleChats = 0
    private var unregister: (() -> Unit)? = null

    fun start(context: Context) {
        visibleChats += 1
        if (visibleChats > 1 || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val manager = context.applicationContext.getSystemService(UiModeManager::class.java) ?: return
        unregister = Listening.register(context.applicationContext, manager) { mutableIncreasedContrast.value = it }
    }

    fun stop() {
        if (visibleChats == 0) return
        visibleChats -= 1
        if (visibleChats > 0) return
        unregister?.invoke()
        unregister = null
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Listening {
        fun register(context: Context, manager: UiModeManager, changed: (Boolean) -> Unit): () -> Unit {
            val listener = UiModeManager.ContrastChangeListener { changed(it > 0f) }
            changed(manager.contrast > 0f)
            manager.addContrastChangeListener(context.mainExecutor, listener)
            return { manager.removeContrastChangeListener(listener) }
        }
    }
}
