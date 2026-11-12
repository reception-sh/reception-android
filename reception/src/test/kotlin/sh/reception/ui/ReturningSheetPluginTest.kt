package sh.reception.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import sh.reception.Reception
import sh.reception.ReceptionChat
import sh.reception.ReceptionInternalApi
import sh.reception.client.ApplicationLifecycle
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness

/**
 * The launcher return through the plugin hook (`Reception.noteNewIntent`): React Native's `ReactActivity` gives new
 * intents to React and the plugin, never to `ComponentActivity`'s listeners (fixes 26, F2), and a host that is no
 * `ComponentActivity` has no listeners at all. The hook feeds the listener's decision, and an intent both deliver
 * counts once.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ReceptionInternalApi::class)
class ReturningSheetPluginTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()
    private lateinit var root: ActivityController<out Activity>
    private lateinit var sheet: ActivityController<ReceptionChatActivity>
    private var token: String? = null

    private val host: Activity
        get() = root.get()

    @After
    fun tearDown() = harness.stop()

    /** React Native's `ReactActivity` once React runs: its delegate handles every new intent, so `super` is not called. */
    class ReactLikeActivity : ComponentActivity() {
        override fun onNewIntent(intent: Intent) {
            Reception.noteNewIntent(this, intent)
        }
    }

    /** The sheet opened over a root of [type], then the person went home. */
    private fun leaveWithTheSheetOpen(type: Class<out Activity>) {
        harness.start()
        root = TestActivities.build(type).setup()
        ApplicationLifecycle.onActivityResumed(host)
        ReceptionChat.open()
        harness.settle()
        val intent = requireNotNull(shadowOf(host).nextStartedActivity)
        token = intent.getStringExtra(SheetLaunch.EXTRA_PRESENTATION)
        root.pause()
        ApplicationLifecycle.onActivityPaused(host)
        sheet = TestActivities.build(ReceptionChatActivity::class.java, intent).setup()
        ApplicationLifecycle.onActivityResumed(sheet.get())
        ApplicationLifecycle.onActivityPaused(sheet.get())
        sheet.pause().stop()
        root.stop()
    }

    private fun systemFinishesTheSheet() {
        sheet.get().finish()
        sheet.destroy()
    }

    private fun resumeTheRoot(): Intent? {
        root.restart().resume()
        ApplicationLifecycle.onActivityResumed(host)
        harness.settle()
        return shadowOf(host).nextStartedActivity
    }

    private fun assertBroughtBack(started: Intent?) {
        assertEquals(token, started?.getStringExtra(SheetLaunch.EXTRA_PRESENTATION))
        assertTrue(started?.getBooleanExtra(SheetLaunch.EXTRA_RETURNING, false) == true)
    }

    private fun assertClosed(started: Intent?) {
        assertNull("the chat stays closed", started)
        assertFalse(ChatPresenter.isPresented)
    }

    @Test
    fun aReactActivityGetsTheSheetBackThroughTheHook() {
        leaveWithTheSheetOpen(ReactLikeActivity::class.java)
        systemFinishesTheSheet()
        root.newIntent(launcherReturn())
        assertBroughtBack(resumeTheRoot())
        root.pause()
        assertNull("it comes back once", resumeTheRoot())
    }

    @Test
    fun aReactActivityThatResumedBeforeTheSheetEndedGetsItBackAtOnce() {
        leaveWithTheSheetOpen(ReactLikeActivity::class.java)
        root.newIntent(launcherReturn())
        assertNull(resumeTheRoot())
        systemFinishesTheSheet()
        harness.settle()
        assertBroughtBack(shadowOf(host).nextStartedActivity)
    }

    @Test
    fun aDeepLinkThroughTheHookLeavesTheChatClosed() {
        leaveWithTheSheetOpen(ReactLikeActivity::class.java)
        systemFinishesTheSheet()
        root.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("example://screen")).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertClosed(resumeTheRoot())
    }

    @Test
    fun aLauncherIntentHandedOverForAnUnwatchedActivityIsIgnored() {
        leaveWithTheSheetOpen(ComponentActivity::class.java)
        val other = TestActivities.build(ComponentActivity::class.java).create().get()
        Reception.noteNewIntent(other, launcherReturn())
        systemFinishesTheSheet()
        assertClosed(resumeTheRoot())
    }

    @Test
    fun anIntentDeliveredByTheListenerAndTheHookCountsOnce() {
        // A host that passes new intents on to its listeners and also hands them to the plugin.
        leaveWithTheSheetOpen(ComponentActivity::class.java)
        val intent = launcherReturn()
        Reception.noteNewIntent(host, intent)
        assertNull(resumeTheRoot())
        // The same intent again after the root's first resume does not start a new launch whose first resume is ahead.
        root.newIntent(intent)
        systemFinishesTheSheet()
        harness.settle()
        assertBroughtBack(shadowOf(host).nextStartedActivity)
    }

    @Test
    fun aHostThatIsNoComponentActivityGetsTheSheetBackThroughTheHook() {
        leaveWithTheSheetOpen(Activity::class.java)
        systemFinishesTheSheet()
        Reception.noteNewIntent(host, launcherReturn())
        assertBroughtBack(resumeTheRoot())
    }

    @Test
    fun aHostThatIsNoComponentActivityIsNotWatchedAfterItsRecreation() {
        // Without a saved-state registry it carries no marker, so its new instance after a rotation is not known.
        leaveWithTheSheetOpen(Activity::class.java)
        val old = host
        Activity::class.java.getDeclaredField("mChangingConfigurations").apply {
            isAccessible = true
            setBoolean(old, true)
        }
        root.destroy()
        ApplicationLifecycle.onActivityDestroyed(old)
        root = TestActivities.build(Activity::class.java).create(Bundle()).start()
        ApplicationLifecycle.onActivityCreated(host, Bundle())
        root.stop()
        systemFinishesTheSheet()
        Reception.noteNewIntent(host, launcherReturn())
        assertClosed(resumeTheRoot())
    }

    @Test
    fun theHookIsHarmlessWithoutAPresentation() {
        val activity = TestActivities.build(Activity::class.java).setup().get()
        Reception.noteNewIntent(activity, launcherReturn())
        ApplicationLifecycle.onActivityResumed(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private companion object {
        /** What the Pixel launcher on Android 16 delivered to the running `singleTask` root (fixes-21). */
        fun launcherReturn(): Intent = Intent.makeMainActivity(null).setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT,
        )
    }
}
