package sh.reception.client

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import sh.reception.AppLocale
import sh.reception.PendingChatOpen
import sh.reception.ReceptionState
import sh.reception.ui.ChatPresenter
import java.lang.ref.WeakReference

/**
 * App activity from the process lifecycle (foreground, background) and the resumed activity, which presents the chat.
 * "Active" is iOS `applicationState == .active`: an activity of the app is resumed.
 */
internal object ApplicationLifecycle : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {
    private var started = false
    private var registering = false
    private var resumed: WeakReference<Activity>? = null

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
        if (!registering) ReceptionState.applicationBecameActive()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!registering) ReceptionState.applicationEnteredBackground()
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
        // A new per-app or system language recreates activities, so the app language is read again.
        AppLocale.invalidate()
        ChatPresenter.activityResumed(activity)
        PendingChatOpen.activityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        SetupCheck.activityCreated(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
