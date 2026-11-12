package sh.reception.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import sh.reception.ReceptionChat
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.PresentationRecord
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness
import java.time.Duration

/**
 * A sheet comes back after, and only after, a return through the launcher icon finished it: a `singleTask` root got the
 * launcher's intent and Android finished the stopped sheet above it. The intents are the ones Android 16 delivered on
 * the emulator.
 */
@RunWith(RobolectricTestRunner::class)
class ReturningSheetTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()
    private lateinit var root: ActivityController<ComponentActivity>
    private lateinit var sheet: ActivityController<ReceptionChatActivity>
    private var token: String? = null

    private val host: ComponentActivity
        get() = root.get()

    @After
    fun tearDown() = harness.stop()

    /** The sheet open over the root, then left: the person went home or another app covers the task. */
    private fun leaveWithTheSheetOpen() {
        openTheSheet()
        leave()
    }

    private fun openTheSheet() {
        harness.start()
        root = TestActivities.build(ComponentActivity::class.java).setup()
        ApplicationLifecycle.onActivityResumed(host)
        ReceptionChat.open()
        harness.settle()
        val intent = requireNotNull(shadowOf(host).nextStartedActivity)
        token = intent.getStringExtra(SheetLaunch.EXTRA_PRESENTATION)
        root.pause()
        ApplicationLifecycle.onActivityPaused(host)
        sheet = TestActivities.build(ReceptionChatActivity::class.java, intent).setup()
        ApplicationLifecycle.onActivityResumed(sheet.get())
    }

    private fun leave() {
        ApplicationLifecycle.onActivityPaused(sheet.get())
        sheet.pause().stop()
        root.stop()
    }

    /** A rotation with the sheet open: Android recreates the sheet on top, then the host below it. */
    private fun rotate() {
        recreate(sheet)
        recreate(root)
    }

    /** Recreates the activity and reports it as Android does: the old one pauses and ends, the new one comes and resumes. */
    private fun recreate(controller: ActivityController<out ComponentActivity>) {
        val old = controller.get()
        val wasResumed = ApplicationLifecycle.currentActivity === old
        if (wasResumed) ApplicationLifecycle.onActivityPaused(old)
        controller.recreate()
        ApplicationLifecycle.onActivityDestroyed(old)
        ApplicationLifecycle.onActivityCreated(controller.get(), Bundle())
        if (wasResumed) ApplicationLifecycle.onActivityResumed(controller.get())
    }

    /** What a new intent for a `singleTask` root does to its task: everything above the root finishes. */
    private fun systemFinishesTheSheet() {
        sheet.get().finish()
        sheet.destroy()
    }

    private fun deliver(intent: Intent) {
        root.newIntent(intent)
    }

    private fun resumeTheRoot(): Intent? {
        root.restart().resume()
        ApplicationLifecycle.onActivityResumed(host)
        harness.settle()
        return shadowOf(host).nextStartedActivity
    }

    private fun assertClosedForGood(started: Intent?) {
        assertNull("the chat stays closed", started)
        assertNull(PresentationRecord.load(RuntimeEnvironment.getApplication()))
        assertFalse(ChatPresenter.isPresented)
        assertEquals(0, listeners(host))
    }

    @Test
    fun aLauncherReturnBringsTheSheetBackOpenOverTheRoot() {
        leaveWithTheSheetOpen()
        assertEquals("the root is listened to while the sheet is presented", 1, listeners(host))
        // Android 16: the sheet ends first, then the root gets the launcher's intent and resumes.
        systemFinishesTheSheet()
        deliver(launcherReturn())
        val again = requireNotNull(resumeTheRoot())
        assertEquals(ReceptionChatActivity::class.java.name, again.component?.className)
        assertEquals(token, again.getStringExtra(SheetLaunch.EXTRA_PRESENTATION))
        assertTrue("it shows open, without its entry", again.getBooleanExtra(SheetLaunch.EXTRA_RETURNING, false))
        assertEquals(token, PresentationRecord.load(RuntimeEnvironment.getApplication())?.token)
        assertEquals("consumed: nothing listens until the new sheet attaches", 0, listeners(host))
        root.pause()
        assertNull("it comes back once", resumeTheRoot())
    }

    @Test
    fun aHostRecreatedWithTheSheetOpenIsWatchedInPlaceOfTheOldOne() {
        openTheSheet()
        val old = host
        rotate()
        assertTrue(old !== host)
        assertEquals("the destroyed host is no longer listened to", 0, listeners(old))
        assertEquals(1, listeners(host))
        leave()
        systemFinishesTheSheet()
        deliver(launcherReturn())
        assertEquals(token, resumeTheRoot()?.getStringExtra(SheetLaunch.EXTRA_PRESENTATION))
    }

    /** Recreates [controller]'s activity and reports [between] (Android's callbacks) after the old one ended. */
    private fun recreateWith(controller: ActivityController<out ComponentActivity>, between: () -> Unit) {
        val old = controller.get()
        controller.recreate()
        ApplicationLifecycle.onActivityDestroyed(old)
        between()
        ApplicationLifecycle.onActivityCreated(controller.get(), Bundle())
    }

    @Test
    fun aFreshActivityOfTheHostsClassCreatedBeforeItsNewInstanceIsNotWatched() {
        openTheSheet()
        recreate(sheet)
        var fresh: ComponentActivity? = null
        // A22-3: a new instance of the host's class, started in the same task between the old host's end and its new
        // instance, used to take over the watch.
        recreateWith(root) {
            fresh = TestActivities.build(ComponentActivity::class.java).create().get()
            ApplicationLifecycle.onActivityCreated(checkNotNull(fresh), null)
        }
        assertEquals(0, listeners(checkNotNull(fresh)))
        assertEquals("the host's new instance is watched", 1, listeners(host))
        leave()
        systemFinishesTheSheet()
        deliver(launcherReturn())
        assertEquals(token, resumeTheRoot()?.getStringExtra(SheetLaunch.EXTRA_PRESENTATION))
    }

    @Test
    fun eachRecreatedHostIsReplacedByItsOwnNewInstanceOnly() {
        // Two hosts of the same class below the sheet: one new instance used to consume both pending recreations.
        val below = TestActivities.build(ComponentActivity::class.java).setup()
        ApplicationLifecycle.onActivityCreated(below.get(), null)
        openTheSheet()
        assertEquals(1, listeners(below.get()))
        recreate(sheet)
        val oldBelow = below.get()
        recreateWith(root) {
            below.recreate()
            ApplicationLifecycle.onActivityDestroyed(oldBelow)
        }
        assertEquals(1, listeners(host))
        assertEquals("not yet: its new instance was not created", 0, listeners(below.get()))
        ApplicationLifecycle.onActivityCreated(below.get(), Bundle())
        assertEquals(1, listeners(below.get()))
        // Each marker is consumed once: reporting the creation again watches nothing more.
        ApplicationLifecycle.onActivityCreated(host, Bundle())
        assertEquals(1, listeners(host))
    }

    /** Android destroys the stopped host for a configuration change; its state was saved before. */
    private fun destroyHostForAConfigurationChange() {
        val old = host
        android.app.Activity::class.java.getDeclaredField("mChangingConfigurations").apply {
            isAccessible = true
            setBoolean(old, true)
        }
        root.destroy()
        ApplicationLifecycle.onActivityDestroyed(old)
    }

    /** The host's new instance, created from [saved], comes and stays stopped below the sheet. */
    private fun createHostFrom(saved: Bundle) {
        root = TestActivities.build(ComponentActivity::class.java).create(saved).start()
        ApplicationLifecycle.onActivityCreated(host, saved)
        root.postCreate(saved).stop()
    }

    private fun assertALauncherReturnBringsTheSheetBack() {
        ApplicationLifecycle.onActivityPaused(sheet.get())
        sheet.pause().stop()
        systemFinishesTheSheet()
        deliver(launcherReturn())
        assertEquals(token, resumeTheRoot()?.getStringExtra(SheetLaunch.EXTRA_PRESENTATION))
    }

    @Test
    fun aHostWhoseStateWasSavedBeforeTheSheetWasRecreatedIsKnownAfterItsOwnRecreation() {
        // A24-3, the sheet first: the stopped host saved its state when the sheet covered it, then a rotation recreated
        // the sheet at once and the host only when Android needed it. The recreated sheet gave the host a new marker.
        openTheSheet()
        root.stop()
        val saved = Bundle().also { root.saveInstanceState(it) }
        recreate(sheet)
        destroyHostForAConfigurationChange()
        createHostFrom(saved)
        assertEquals(1, listeners(host))
        assertALauncherReturnBringsTheSheetBack()
    }

    @Test
    fun aLauncherRootSavedBeforeTheChatOpenedIsKnownAfterItsRecreation() {
        // A25-3: root A started B and saved its state when it stopped; B opened the chat, so A's bundle predates it.
        harness.start()
        root = TestActivities.build(ComponentActivity::class.java).setup()
        ApplicationLifecycle.onActivityResumed(host)
        root.pause()
        ApplicationLifecycle.onActivityPaused(host)
        val saved = Bundle().also { root.saveInstanceState(it) }
        root.stop()
        val upper = TestActivities.build(ComponentActivity::class.java).setup()
        ApplicationLifecycle.onActivityResumed(upper.get())
        ReceptionChat.open()
        harness.settle()
        val intent = requireNotNull(shadowOf(upper.get()).nextStartedActivity)
        token = intent.getStringExtra(SheetLaunch.EXTRA_PRESENTATION)
        upper.pause()
        ApplicationLifecycle.onActivityPaused(upper.get())
        sheet = TestActivities.build(ReceptionChatActivity::class.java, intent).setup()
        ApplicationLifecycle.onActivityResumed(sheet.get())
        assertEquals(1, listeners(host))
        recreate(sheet)
        destroyHostForAConfigurationChange()
        createHostFrom(saved)
        assertEquals("A's new instance is watched", 1, listeners(host))
        upper.pause().stop()
        assertALauncherReturnBringsTheSheetBack()
    }

    @Test
    fun aHostDestroyedBeforeTheSheetWasRecreatedIsWatchedInItsNewInstance() {
        // A24-3, the host first: its new instance comes only after the sheet's; the recreated sheet dropped the wait.
        openTheSheet()
        root.stop()
        val saved = Bundle().also { root.saveInstanceState(it) }
        destroyHostForAConfigurationChange()
        recreate(sheet)
        createHostFrom(saved)
        assertEquals(1, listeners(host))
        // A host recreated before the sheet stays watched with its marker when the sheet is recreated after it.
        recreate(sheet)
        assertEquals(1, listeners(host))
        val again = Bundle().also { root.saveInstanceState(it) }
        destroyHostForAConfigurationChange()
        createHostFrom(again)
        assertEquals(1, listeners(host))
        assertALauncherReturnBringsTheSheetBack()
    }

    @Test
    fun aDestroyedHostNoLongerSavesItsMarker() {
        // A24-4: its listener went, its saved-state provider stayed registered.
        leaveWithTheSheetOpen()
        val old = host
        old.finish()
        root.destroy()
        ApplicationLifecycle.onActivityDestroyed(old)
        assertNull(old.savedStateRegistry.getSavedStateProvider(MARKER_KEY))
    }

    @Test
    fun aDestroyedHostIsNoLongerListenedTo() {
        leaveWithTheSheetOpen()
        host.finish()
        root.destroy()
        ApplicationLifecycle.onActivityDestroyed(host)
        assertEquals(0, listeners(host))
    }

    @Test
    fun aNewActivityCreatedWhileTheSheetIsOpenIsNotWatched() {
        openTheSheet()
        val other = TestActivities.build(ComponentActivity::class.java).create()
        ApplicationLifecycle.onActivityCreated(other.get(), null)
        assertEquals(0, listeners(other.get()))
    }

    @Test
    fun aRootThatGotTheLauncherIntentAndResumedBeforeTheSheetEndedGetsItBackAtOnce() {
        leaveWithTheSheetOpen()
        deliver(launcherReturn())
        assertNull(resumeTheRoot())
        systemFinishesTheSheet()
        harness.settle()
        assertEquals(token, shadowOf(host).nextStartedActivity?.getStringExtra(SheetLaunch.EXTRA_PRESENTATION))
        assertTrue("the started sheet counts as presented until it attaches", ChatPresenter.isPresented)
    }

    /** Another of the host's screens comes to the front and is left again, as a quick in-app navigation would. */
    private fun anotherScreenResumesAndPauses() {
        val other = TestActivities.build(ComponentActivity::class.java).setup()
        ApplicationLifecycle.onActivityResumed(other.get())
        other.pause()
        ApplicationLifecycle.onActivityPaused(other.get())
    }

    @Test
    fun anotherScreenResumingBetweenTheLauncherIntentAndTheSheetsEndLeavesTheChatClosed() {
        leaveWithTheSheetOpen()
        deliver(launcherReturn())
        anotherScreenResumesAndPauses()
        systemFinishesTheSheet()
        assertClosedForGood(resumeTheRoot())
    }

    @Test
    fun anotherScreenResumingAfterTheRootResumedFirstLeavesTheChatClosed() {
        leaveWithTheSheetOpen()
        deliver(launcherReturn())
        assertNull(resumeTheRoot())
        root.pause()
        ApplicationLifecycle.onActivityPaused(host)
        anotherScreenResumesAndPauses()
        systemFinishesTheSheet()
        assertClosedForGood(resumeTheRoot())
    }

    @Test
    fun aRootThatResumedFirstAndWasLeftBeforeTheSheetEndedLeavesTheChatClosed() {
        leaveWithTheSheetOpen()
        deliver(launcherReturn())
        assertNull(resumeTheRoot())
        root.pause()
        ApplicationLifecycle.onActivityPaused(host)
        systemFinishesTheSheet()
        assertClosedForGood(resumeTheRoot())
    }

    private fun returnsThrough(intent: Intent) {
        leaveWithTheSheetOpen()
        systemFinishesTheSheet()
        deliver(intent)
        assertClosedForGood(resumeTheRoot())
    }

    @Test
    fun theHostsNotificationWithTheAppsLaunchIntentLeavesTheChatClosed() =
        returnsThrough(Intent.makeMainActivity(null).setFlags(NEW_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT))

    @Test
    fun theHostsNavigationIntoTheRootLeavesTheChatClosed() =
        returnsThrough(Intent().setFlags(NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT))

    @Test
    fun aDeepLinkLeavesTheChatClosed() =
        returnsThrough(Intent(Intent.ACTION_VIEW, Uri.parse("example://screen")).setFlags(NEW_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT))

    @Test
    fun aLauncherShortcutLeavesTheChatClosed() =
        returnsThrough(Intent(Intent.ACTION_VIEW).setFlags(NEW_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT))

    @Test
    fun onlyTheLaunchersIntentCountsAsALauncherReturn() {
        assertTrue(ReturningSheet.isLauncherLaunch(launcherReturn()))
        assertFalse(ReturningSheet.isLauncherLaunch(Intent.makeMainActivity(null).setFlags(NEW_TASK)))
        assertFalse(ReturningSheet.isLauncherLaunch(launcherReturn().setData(Uri.parse("example://screen"))))
        assertFalse(ReturningSheet.isLauncherLaunch(Intent(Intent.ACTION_MAIN).setFlags(LAUNCHER_FLAGS)))
    }

    @Test
    fun aCoveringScreensClearTopNavigationAfterTheAppWasAwayLeavesTheChatClosed() {
        leaveWithTheSheetOpen()
        // The person returned to a host screen covering the sheet, which then navigates to the root with CLEAR_TOP.
        val cover = TestActivities.build(ComponentActivity::class.java).setup()
        ApplicationLifecycle.onActivityResumed(cover.get())
        cover.pause()
        ApplicationLifecycle.onActivityPaused(cover.get())
        systemFinishesTheSheet()
        deliver(Intent().setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertClosedForGood(resumeTheRoot())
    }

    @Test
    fun aSheetFinishedWhileShownDoesNotComeBack() {
        leaveWithTheSheetOpen()
        sheet.restart().resume()
        // The host started its root with a launch intent in front of the person: the shown sheet finishes first.
        deliver(launcherReturn())
        sheet.get().finish()
        sheet.pause().stop().destroy()
        assertClosedForGood(resumeTheRoot())
    }

    @Test
    fun aLauncherIntentLongAfterTheSheetEndedDoesNotBringItBack() {
        leaveWithTheSheetOpen()
        systemFinishesTheSheet()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        deliver(launcherReturn())
        assertClosedForGood(resumeTheRoot())
    }

    @Test
    fun aChatClosedWhileTheAppWasAwayStaysClosed() {
        leaveWithTheSheetOpen()
        systemFinishesTheSheet()
        ReceptionChat.close()
        assertEquals(0, listeners(host))
        deliver(launcherReturn())
        assertClosedForGood(resumeTheRoot())
    }

    @Test
    fun aSheetTheSdkClosedDoesNotComeBack() {
        leaveWithTheSheetOpen()
        ChatPresenter.dismiss()
        sheet.get().dismiss()
        sheet.destroy()
        deliver(launcherReturn())
        assertClosedForGood(resumeTheRoot())
    }

    @Test
    fun aSheetClosedByThePersonStopsListening() {
        leaveWithTheSheetOpen()
        sheet.restart().resume()
        // What the sheet's drag, scrim tap, Back or close button does once its slide ends.
        ChatPresenter.detached(sheet.get())
        sheet.get().finish()
        sheet.pause().stop().destroy()
        assertEquals(0, listeners(host))
        harness.settle()
        assertNull(PresentationRecord.load(RuntimeEnvironment.getApplication()))
    }

    private companion object {
        const val MARKER_KEY = "sh.reception.returningSheet"
        const val NEW_TASK = Intent.FLAG_ACTIVITY_NEW_TASK
        const val LAUNCHER_FLAGS =
            NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT

        /** What the Pixel launcher on Android 16 delivered to the running `singleTask` root (fixes-21). */
        fun launcherReturn(): Intent = Intent.makeMainActivity(null).setFlags(LAUNCHER_FLAGS)

        /** The SDK's listeners on [activity] (`ComponentActivity` offers no getter). */
        fun listeners(activity: ComponentActivity): Int {
            val field = ComponentActivity::class.java.getDeclaredField("onNewIntentListeners").apply { isAccessible = true }
            return (field.get(activity) as Collection<*>).size
        }
    }
}
