package sh.reception.ui

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.util.Consumer
import sh.reception.Log
import sh.reception.client.ApplicationLifecycle
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Brings back a sheet that a return through the launcher icon finished, and nothing else. A `singleTask` launcher
 * activity (Expo's default) gets the launcher's intent when the person taps the app icon, and Android finishes every
 * activity above it, the sheet included, which neither the person nor the app closed. The sheet comes back only when:
 * - one of the host's activities below the sheet in its task, watched while the sheet is presented, received a launcher
 *   launch ([isLauncherLaunch]): through its new-intent listener (a `ComponentActivity`) or handed over by a plugin
 *   ([newIntent]);
 * - the system finished the sheet while it was stopped and open (not the SDK, not the person;
 *   [ReceptionChatActivity.stoppedOpen]) within [RELAUNCH_WINDOW_MILLIS] of that intent, and its presentation is still the current one;
 * - that activity is the next one to resume, in the sheet's task: no other activity of the app resumes between the
 *   intent and the sheet's return, and the root itself resumes once, before or after the sheet ends. Then the sheet
 *   starts over it once.
 *
 * Every other finish (a notification, a deep link, a shortcut, a widget or the host's own navigation with
 * `FLAG_ACTIVITY_CLEAR_TOP`, Recents removal, `finishAffinity`) keeps Android's behaviour: the chat stays closed. The
 * activity's own `intent` is never read: `onNewIntent` does not replace it. Main thread only.
 */
internal object ReturningSheet {
    /** The system finishes the sheet and delivers the launcher's intent in one relaunch, milliseconds apart. */
    private const val RELAUNCH_WINDOW_MILLIS = 1_000L

    /**
     * A watched host, held weakly. A `ComponentActivity` keeps it as its new-intent listener, the SDK keeps both only to
     * remove it again, and [marker] is the activity's [ActivityIdentity], so the instance a configuration change recreates
     * from its saved state is known. Any other activity (Flutter's `FlutterActivity`) offers neither: its new intents come
     * only from a plugin ([newIntent]) and it has no marker, so its instance recreated by a configuration change is not
     * watched.
     */
    private class Host(activity: Activity, val marker: String?) : Consumer<Intent> {
        val activity = WeakReference(activity)

        override fun accept(value: Intent) {
            activity.get()?.let { received(it, value) }
        }
    }

    private class Launch(val activity: WeakReference<Activity>, val intent: Intent, val at: Long) {
        /** The activity resumed after the intent while the sheet still existed: the order where the root resumes first. */
        var resumedFirst = false
    }

    private class Finished(val token: String, val task: Int, val at: Long)

    private val hosts = mutableListOf<Host>()

    /** The markers of watched hosts destroyed for a configuration change, each waiting for its new instance. */
    private val recreating = mutableSetOf<String>()
    private var launch: Launch? = null
    private var finished: Finished? = null

    /**
     * The launcher's start of the app: `ACTION_MAIN` in `CATEGORY_LAUNCHER` without data, with
     * `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`, which launchers add and the app's own launch intent
     * (`getLaunchIntentForPackage`, a notification or widget built from it) does not.
     */
    fun isLauncherLaunch(intent: Intent): Boolean =
        intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.data == null &&
            intent.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0

    /**
     * [sheet] is presented: the host activities below it in its task are listened to until the presentation ends. A
     * sheet recreated for a configuration change comes here again and only adds hosts not watched yet, and a host
     * destroyed meanwhile keeps waiting for its new instance.
     */
    fun watch(sheet: ReceptionChatActivity) {
        for (activity in ApplicationLifecycle.aliveActivities()) {
            if (activity === sheet || activity.taskId != sheet.taskId) continue
            if (hosts.none { it.activity.get() === activity }) listen(activity)
        }
    }

    private fun listen(activity: Activity) {
        val host = Host(activity, ActivityIdentity.of(activity))
        if (activity is ComponentActivity) activity.addOnNewIntentListener(host)
        hosts += host
    }

    private fun unlisten(activity: Activity, host: Host) {
        if (activity is ComponentActivity) activity.removeOnNewIntentListener(host)
    }

    /**
     * An activity of the app was created: the new instance of a watched host that a configuration change (a rotation
     * with the sheet open) recreated, the one whose saved state restores that host's identity, is watched for the
     * current presentation, once; any other new activity is not, also not one of the same class created in between.
     * Every new activity gets its identity here.
     */
    fun created(activity: Activity) {
        val identity = ActivityIdentity.of(activity) ?: return
        if (recreating.remove(identity)) listen(activity)
    }

    /**
     * An activity of the app was destroyed: a watched host is no longer listened to, its marker no longer saved (Android
     * saved its state before), and its launcher intent is dropped.
     */
    fun destroyed(activity: Activity) {
        ActivityIdentity.forget(activity)
        val host = hosts.find { it.activity.get() === activity } ?: return
        hosts -= host
        unlisten(activity, host)
        if (launch?.activity?.get() === activity) launch = null
        if (activity.isChangingConfigurations) host.marker?.let { recreating += it }
    }

    /**
     * A plugin hands over a new intent of the activity it is attached to (`Reception.noteNewIntent`), because React
     * Native's `ReactActivity` gives new intents to React instead of `ComponentActivity`'s listeners once React runs. It
     * counts as the listener's delivery would, and only for a watched host.
     */
    fun newIntent(activity: Activity, intent: Intent) {
        if (hosts.any { it.activity.get() === activity }) received(activity, intent)
    }

    private fun received(activity: Activity, intent: Intent) {
        // A host that passes new intents on to its listeners and a plugin both deliver the same one: the first counts.
        if (launch?.intent === intent) return
        launch = if (isLauncherLaunch(intent)) Launch(WeakReference(activity), intent, SystemClock.uptimeMillis()) else null
    }

    /**
     * The system finished the sheet of the current presentation [token] while it was stopped and open. An activity
     * already [resumed] decides now: the sheet comes back only over a root that got the launcher's intent and resumed
     * as the next activity before the sheet ended. Without one, the next resumed activity decides ([resumed]).
     */
    fun finishedBySystem(token: String, task: Int, resumed: Activity?): Decision? {
        val sheet = Finished(token, task, SystemClock.uptimeMillis())
        if (resumed == null) {
            finished = sheet
            return null
        }
        return decide(sheet, resumed, resumedFirst = true)
    }

    /** A presentation's [token] and the activity its sheet comes back over, null when it stays closed. */
    class Decision(val token: String, val host: Activity?)

    /**
     * An activity resumed. While the sheet still exists, only the first resume of the activity that got the launcher's
     * intent keeps that intent; any other resume drops it. Once the system finished the sheet, the resume decides, once.
     */
    fun resumed(activity: Activity): Decision? {
        val sheet = finished
        if (sheet != null) return decide(sheet, activity, resumedFirst = false)
        val signal = launch ?: return null
        if (signal.activity.get() === activity && !signal.resumedFirst) signal.resumedFirst = true else launch = null
        return null
    }

    private fun decide(sheet: Finished, activity: Activity, resumedFirst: Boolean): Decision {
        val signal = launch
        finished = null
        launch = null
        stopWatching()
        val returns = signal != null && signal.resumedFirst == resumedFirst && signal.activity.get() === activity &&
            activity.taskId == sheet.task && !activity.isFinishing && abs(signal.at - sheet.at) <= RELAUNCH_WINDOW_MILLIS
        if (!returns) Log.debug { "Chat not brought back, the app did not return through its launcher icon" }
        return Decision(sheet.token, activity.takeIf { returns })
    }

    /** The presentation ended or another started; answers whether a finished sheet was waiting for its decision. */
    fun cancel(): Boolean {
        val wasWaiting = finished != null
        finished = null
        launch = null
        stopWatching()
        return wasWaiting
    }

    private fun stopWatching() {
        hosts.forEach { host -> host.activity.get()?.let { unlisten(it, host) } }
        hosts.clear()
        recreating.clear()
    }
}
