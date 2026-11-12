package sh.reception.testing

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

/**
 * The activities and dialogs a test creates. Robolectric keeps the main looper, the window manager and Compose's
 * recomposer and snapshot state for every test of a sandbox, so an activity a test leaves alive (its windows, dialogs
 * and compositions) runs on into the next test. [closeAll] dismisses each dialog and destroys each activity through
 * its lifecycle, newest first, then runs the main looper's due work ([MainLooperDrain]); tests call it before they
 * reset the SDK.
 */
internal object TestActivities {
    private val controllers = mutableListOf<ActivityController<*>>()
    private val dialogs = mutableListOf<Dialog>()

    fun <T : Activity> build(type: Class<T>, intent: Intent? = null): ActivityController<T> =
        Robolectric.buildActivity(type, intent).also { controllers += it }

    /** Shows [dialog] and dismisses it at [closeAll] if the test has not. */
    fun <T : Dialog> show(dialog: T): T = dialog.also {
        dialogs += it
        it.show()
    }

    fun closeAll() {
        dialogs.asReversed().forEach { if (it.isShowing) it.dismiss() }
        dialogs.clear()
        ShadowLooper.idleMainLooper()
        controllers.asReversed().forEach { if (!it.get().isDestroyed) it.close() }
        controllers.clear()
        MainLooperDrain.drain()
    }
}
