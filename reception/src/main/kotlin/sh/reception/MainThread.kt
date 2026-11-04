package sh.reception

import android.os.Handler
import android.os.Looper

/** Facade calls run on the main thread, like `@MainActor` on iOS; a call from another thread is posted there. */
internal object MainThread {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun run(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }
}
