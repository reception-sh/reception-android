package sh.reception.client

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.VisibleForTesting
import sh.reception.Log

/**
 * The SDK works in the application's main process only, the one `ApplicationInfo.processName` names. Its state, its
 * storage thread and its presentation tokens live in one process's memory, while SharedPreferences, the caches and the
 * Keystore key are shared by every process of the app; two processes would write the same files with different
 * generations, and a sheet started from another process's activity runs in the main one, where its token is unknown.
 * The SDK's own activities and service declare no process, so they run there.
 *
 * The answer is resolved once per process and never throws: the SDK is called from a host's `Application.onCreate`,
 * which runs in every process of the app, an isolated one included. No disk I/O. Any thread.
 */
internal object AppProcess {
    /** What this process is, as its refusals name it. */
    private class Resolution(val isMain: Boolean, val description: String)

    /** Isolated uids are an app id range of each user; `Process.isIsolated()` is public from Android 9 only. */
    private const val PER_USER_RANGE = 100_000
    private const val FIRST_ISOLATED_APP_ID = 99_000
    private const val LAST_ISOLATED_APP_ID = 99_999

    // Asked before Android 9 only, where no hidden API is restricted and this public static method of ActivityThread
    // is what `Application.getProcessName()` returns from 9; a failure is caught and the process list asked instead.
    @SuppressLint("PrivateApi")
    private val activityThreadName: (Context) -> String? = { _ ->
        Class.forName("android.app.ActivityThread").getMethod("currentProcessName").invoke(null) as? String
    }

    private val runningProcessName: (Context) -> String? = { context ->
        val pid = Process.myPid()
        context.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }?.processName
    }

    @Volatile
    private var resolution: Resolution? = null

    /**
     * Where the name comes from before Android 9, in order. `ActivityThread.currentProcessName()` is the value
     * `Application.getProcessName()` returns from 9, unrestricted before; the process list is the platform's answer for
     * a caller the first source fails, and it refuses an isolated caller, which never reaches it.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    var legacyNameSources: List<(Context) -> String?> = listOf(activityThreadName, runningProcessName)

    fun isMain(context: Context): Boolean = resolved(context).isMain

    /** This process as a refusal names it: its name, or what it is when it has none to compare. */
    fun description(context: Context): String = resolved(context).description

    private fun resolved(context: Context): Resolution = resolution ?: resolve(context).also { resolution = it }

    private fun resolve(context: Context): Resolution {
        if (isIsolated()) return Resolution(isMain = false, description = "an isolated process")
        val expected = context.applicationInfo.processName ?: context.packageName
        val name = name(context)
        if (name != null) return Resolution(isMain = name == expected, description = name)
        // Practically unreachable: both legacy sources failed. Refusing would switch the SDK off in a host's only
        // process; a second process of the app whose name is also unknown needs that failure twice.
        Log.error { "Process name unknown, Reception treats this process as the app's main process" }
        return Resolution(isMain = true, description = "a process of unknown name")
    }

    private fun isIsolated(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Process.isIsolated()
        val appId = Process.myUid() % PER_USER_RANGE
        return appId in FIRST_ISOLATED_APP_ID..LAST_ISOLATED_APP_ID
    }

    private fun name(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        return legacyNameSources.firstNotNullOfOrNull { source ->
            // Reflection and a binder call into the system: either can fail in a host's process; the next source tries.
            try {
                source(context)
            } catch (error: ReflectiveOperationException) {
                null.also { Log.debug { "Process name source failed, ${error.javaClass.simpleName}" } }
            } catch (error: RuntimeException) {
                null.also { Log.debug { "Process name source failed, ${error.javaClass.simpleName}" } }
            }
        }
    }

    /** A new process: the next question resolves again; tests may say which process this is. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun resetForTests(main: Boolean? = null) {
        resolution = main?.let { Resolution(isMain = it, description = if (it) "main" else "another process") }
        legacyNameSources = listOf(activityThreadName, runningProcessName)
    }
}
