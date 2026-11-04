package sh.reception.client

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.BadParcelableException
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.reception.Log
import sh.reception.ReceptionState
import sh.reception.model.MessageStatus
import sh.reception.viewmodel.send

/**
 * Lets a coding agent prove a new integration without tapping the UI. Started with the intent extra
 * `ReceptionSetupCheck=<App ID>` on an activity of a debuggable app (SPEC.md, Setup check), it sends "Hello from setup"
 * once through the normal send path and logs whether Reception accepted it. Release builds ignore the extra. The extra
 * is read from each created activity and from the activity a plugin hands over (`Reception.noteCurrentActivity`), so
 * hosts that configure after their activity exists run the same one-step check.
 */
internal object SetupCheck {
    const val EXTRA: String = "ReceptionSetupCheck"
    const val TEXT: String = "Hello from setup"
    private const val TIMEOUT_MILLIS = 60_000L
    private const val POLL_MILLIS = 200L

    /** What can end the check, for its log line. */
    enum class Outcome(val reason: String?) {
        PASSED(null), APP_ID_MISMATCH("app_id_mismatch"), SEND_NOT_ALLOWED("send_not_allowed"),
        NOT_DELIVERED("not_delivered"), TIMEOUT("timeout"), APP_NOT_ACTIVE("app_not_active"),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var requested: String? = null
    private var configuredAppId: String? = null
    private var started = false

    fun activityCreated(activity: Activity) {
        if (started || requested != null || !isDebuggable(activity)) return
        requested = try {
            activity.intent?.getStringExtra(EXTRA)
        } catch (_: BadParcelableException) {
            null
        } ?: return
        startIfReady()
    }

    fun configured(context: Context, appId: String) {
        if (!isDebuggable(context)) return
        configuredAppId = appId
        startIfReady()
    }

    private fun startIfReady() {
        val requested = requested ?: return
        val appId = configuredAppId ?: return
        if (started) return
        started = true
        if (requested != appId) return report(Outcome.APP_ID_MISMATCH)
        scope.launch { report(run()) }
    }

    private suspend fun run(): Outcome {
        Log.unconditional(failure = false, "Setup check started")
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        while (!ApplicationLifecycle.isActive) {
            if (SystemClock.elapsedRealtime() >= deadline) return Outcome.APP_NOT_ACTIVE
            delay(POLL_MILLIS)
        }
        val chat = ReceptionState.chatModel()
        // Sending needs the stored history.
        chat.restored()
        val earlier = chat.messages.map { it.id }.toSet()
        chat.send(TEXT, emptyList())
        // The send guards (verified-only apps, blocked devices, cooldowns) return without adding a message.
        val id = chat.messages.firstOrNull { it.id !in earlier }?.id ?: return Outcome.SEND_NOT_ALLOWED
        while (SystemClock.elapsedRealtime() < deadline) {
            // A confirmed message carries the draft's ID as its client ID.
            val status = ReceptionState.chatModel().messages.firstOrNull { it.id == id || it.clientId == id }?.status
            outcome(status)?.let { return it }
            delay(POLL_MILLIS)
        }
        return Outcome.TIMEOUT
    }

    /** The check's end for the message's current status, or null while it is still on its way. */
    fun outcome(status: MessageStatus?): Outcome? = when (status) {
        MessageStatus.SENT -> Outcome.PASSED
        MessageStatus.FAILED -> Outcome.NOT_DELIVERED
        else -> null
    }

    private fun report(outcome: Outcome) {
        if (outcome == Outcome.PASSED) {
            Log.unconditional(failure = false, "Setup check passed")
        } else {
            Log.unconditional(failure = true, "Setup check failed: ${outcome.reason}")
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun resetForTests() {
        requested = null
        configuredAppId = null
        started = false
    }

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
