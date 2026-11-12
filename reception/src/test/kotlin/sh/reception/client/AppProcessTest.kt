package sh.reception.client

import android.app.ActivityManager
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowProcess
import sh.reception.Log
import sh.reception.Reception
import sh.reception.ReceptionInternalApi
import sh.reception.ReceptionState
import sh.reception.testing.MainLooperDrain
import java.io.File

/** The SDK works only in the app's main process; in any other it configures nothing and handles no push message. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ReceptionInternalApi::class)
class AppProcessTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    /** The platform's answer to an isolated caller before Android 9 (`enforceNotIsolatedCaller`). */
    @Implements(ActivityManager::class)
    class IsolatedCallerProcessList {
        @Implementation
        fun getRunningAppProcesses(): List<ActivityManager.RunningAppProcessInfo>? =
            throw SecurityException("Isolated process not allowed to call getRunningAppProcesses")
    }

    private val application = RuntimeEnvironment.getApplication()
    private val errors = mutableListOf<String>()
    private val other = "${application.packageName}:other"
    private val main = application.applicationInfo.processName ?: application.packageName

    @After
    fun tearDown() {
        Log.handler = null
        ReceptionState.resetForTests()
    }

    private fun recordErrors() {
        Log.handler = { level, line -> if (level == Reception.LogLevel.ERROR) errors += line }
    }

    private fun isolate() = ShadowProcess.setUid(99_042)

    private fun listProcesses(vararg processes: ActivityManager.RunningAppProcessInfo) =
        shadowOf(application.getSystemService(ActivityManager::class.java)).setProcesses(processes.toList())

    private fun ours(name: String) = ActivityManager.RunningAppProcessInfo(name, android.os.Process.myPid(), null)

    @Test
    @Config(sdk = [24], shadows = [IsolatedCallerProcessList::class])
    fun anIsolatedProcessIsRefusedBeforeAndroid9WithoutAskingThePlatform() {
        isolate()
        AppProcess.legacyNameSources = listOf({ _ -> throw AssertionError("an isolated process asks for no name") })
        recordErrors()
        ReceptionState.configure(application, "app_otherOtherOtherOther01")
        assertNull(ReceptionState.session)
        assertEquals(
            listOf("Configure refused, Reception works only in the app's main process; this is an isolated process"),
            errors,
        )
    }

    @Test
    @Config(sdk = [34])
    fun anIsolatedProcessIsRefusedFromAndroid9() {
        isolate()
        assertFalse(AppProcess.isMain(application))
        assertEquals("an isolated process", AppProcess.description(application))
    }

    @Test
    @Config(sdk = [24])
    fun beforeAndroid9TheNameComesFromTheActivityThreadFirst() {
        AppProcess.legacyNameSources = listOf({ _ -> other }, { _ -> throw AssertionError("not asked") })
        assertFalse(AppProcess.isMain(application))
        assertEquals(other, AppProcess.description(application))
    }

    @Test
    @Config(sdk = [24])
    fun theActivityThreadOfThisPlatformNamesTheProcess() {
        listProcesses()
        assertTrue(AppProcess.isMain(application))
        assertEquals(main, AppProcess.description(application))
    }

    @Test
    @Config(sdk = [24])
    fun aFailingActivityThreadFallsBackToTheRunningProcessWithOurPid() {
        val failing = AppProcess.legacyNameSources.last()
        AppProcess.legacyNameSources = listOf({ _ -> throw NoSuchMethodException("currentProcessName") }, failing)
        listProcesses(ActivityManager.RunningAppProcessInfo(main, android.os.Process.myPid() + 1, null), ours(other))
        assertFalse(AppProcess.isMain(application))
        assertEquals(other, AppProcess.description(application))
    }

    @Test
    @Config(sdk = [24], shadows = [IsolatedCallerProcessList::class])
    fun whenEverySourceFailsTheProcessCountsAsMainAndSaysSoOnce() {
        val processList = AppProcess.legacyNameSources.last()
        AppProcess.legacyNameSources = listOf({ _ -> throw ClassNotFoundException("android.app.ActivityThread") }, processList)
        recordErrors()
        assertTrue(AppProcess.isMain(application))
        assertTrue(AppProcess.isMain(application))
        assertEquals("a process of unknown name", AppProcess.description(application))
        assertEquals(listOf("Process name unknown, Reception treats this process as the app's main process"), errors)
    }

    @Test
    @Config(sdk = [34])
    fun theMainProcessIsTheOneTheApplicationInfoNames() {
        // Android fills processName with the package name when the manifest names none; Robolectric leaves it null.
        assertTrue(AppProcess.isMain(application))
        assertEquals(main, AppProcess.description(application))
    }

    @Test
    @Config(sdk = [24], shadows = [IsolatedCallerProcessList::class])
    fun everyEntryPointOfAnIsolatedProcessBeforeAndroid9DoesNothing() = refusedProcessDoesNothing { isolate() }

    @Test
    @Config(sdk = [34])
    fun everyEntryPointOfAnIsolatedProcessDoesNothing() = refusedProcessDoesNothing { isolate() }

    @Test
    @Config(sdk = [24])
    fun everyEntryPointOfAnotherProcessDoesNothing() =
        refusedProcessDoesNothing { AppProcess.legacyNameSources = listOf({ _ -> other }) }

    /** Every public entry point a host's other process may reach: no crash, no file, no request, no notification. */
    private fun refusedProcessDoesNothing(enterProcess: () -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            enterProcess()
            val files = filesOf(application.dataDir)
            recordErrors()
            Reception.configure(application, "app_otherOtherOtherOther01")
            Reception.configureForTesting(application, "app_otherOtherOtherOther01", server.url("/").toString())
            assertFalse(Reception.isConfigured)
            assertTrue(errors.all { it.startsWith("Configure refused, Reception works only in the app's main process") })
            assertEquals(2, errors.size)

            // Reception's payload stays Reception's (the host shows nothing of its own), but nothing is posted or stored.
            val data = mapOf("reception" to "c1", "title" to "Team", "body" to "Hi")
            assertTrue(Reception.handlePushMessage(application, data, "0:1700000000000099%abc"))
            assertTrue(Reception.handlePushMessage(application, data))
            assertFalse(Reception.handlePushMessage(application, mapOf("other" to "x"), null))
            assertTrue(Reception.handlePushNotification(data, openChat = false))
            Reception.setPushToken("token")
            Reception.registerPushTokenIfAuthorized("token")
            Reception.registerForPushIfAuthorized()
            Reception.identify("user")
            Reception.track("event")
            Reception.openChat()
            Reception.closeChat()
            Reception.logout()
            Reception.deleteData { }
            runBlocking { Storage.run {} }
            ShadowLooper.idleMainLooper()

            assertEquals(2, errors.count { it == "Push message not handled, Reception works only in the app's main process" })
            assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
            assertEquals(files, filesOf(application.dataDir))
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun filesOf(directory: File): Set<String> =
        directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).path }.toSet()
}
