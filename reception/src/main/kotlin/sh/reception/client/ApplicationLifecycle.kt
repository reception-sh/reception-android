package sh.reception.client

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import sh.reception.AppLocale
import sh.reception.PendingChatOpen
import sh.reception.ReceptionLifecycle
import sh.reception.ui.ChatPresenter
import sh.reception.ui.ActivityIdentity
import sh.reception.ui.ReturningSheet
import java.lang.ref.WeakReference

/**
 * App activity from the process lifecycle (foreground, background) and the resumed activity, which presents the chat.
 * "Active" is iOS `applicationState == .active`: an activity of the app is resumed.
 */
internal object ApplicationLifecycle : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {
    private var started = false
    private var registering = false
    private var resumed: WeakReference<Activity>? = null
    /** The app's activities that were created or resumed and are not destroyed yet. */
    private val activities = mutableListOf<WeakReference<Activity>>()

    val isActive: Boolean
        get() = started && ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** The activity the chat presents over; null in the background. */
    val currentActivity: Activity?
        get() = resumed?.get()

    fun start(application: Application) {
        if (started) return
        started = true
        // Registration replays the current state; configure has already handled it.
        registering = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registering = false
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        if (!registering) ReceptionLifecycle.applicationBecameActive()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!registering) ReceptionLifecycle.applicationEnteredBackground()
    }

    /**
     * The app's activities that are alive and not finishing. One created before configure (a Flutter or React Native
     * host's) is known from its first resume or `noteCurrentActivity`.
     */
    fun aliveActivities(): List<Activity> = activities.mapNotNull { it.get() }.filter { !it.isFinishing && !it.isDestroyed }

    private fun track(activity: Activity) {
        activities.removeAll { it.get().let { known -> known == null || known === activity } }
        activities += WeakReference(activity)
    }

    /**
     * A host that configures after its activity resumed (the Flutter and React Native plugins) hands that activity
     * over, so the chat and a pending tap have an activity before the next resume. That activity was created before
     * the lifecycle callbacks were registered, so its setup-check extra is read here.
     */
    fun noteCurrentActivity(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        SetupCheck.activityCreated(activity)
        onActivityResumed(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        track(activity)
        // One created before configure gets its identity here (`ActivityIdentity`), before Android saves its state.
        ActivityIdentity.of(activity)
        // A new per-app or system language recreates activities, so the app language is read again.
        AppLocale.invalidate()
        ChatPresenter.activityResumed(activity)
        PendingChatOpen.activityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        track(activity)
        SetupCheck.activityCreated(activity)
        ReturningSheet.created(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** What a new process knows of its activities: none; the callbacks stay registered. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun resetForTests() {
        resumed = null
        activities.clear()
    }

    override fun onActivityDestroyed(activity: Activity) {
        activities.removeAll { it.get().let { known -> known == null || known === activity } }
        ReturningSheet.destroyed(activity)
    }
}
