package sh.reception.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import sh.reception.Reception
import sh.reception.ReceptionChatView
import sh.reception.ReceptionState
import sh.reception.client.ReceptionAPI
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities
import sh.reception.viewmodel.ChatHarness
import java.util.concurrent.TimeUnit

/**
 * A process that Android restarts for a photo picker's or permission dialog's result restores the chat's launchers at
 * their saved keys, whatever happened in the earlier process before (here a logout), so the result reaches the chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatContentRestoreTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val harness = ChatHarness()

    @After
    fun tearDown() = harness.stop()

    private fun ActivityController<ComponentActivity>.showChat(): ActivityController<ComponentActivity> = apply {
        get().setContent { ReceptionChatView() }
    }

    /** Composition and layout, where the composer's launchers register, have run. */
    private fun settled() = repeat(3) {
        harness.settle()
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    }

    /** The keys the activity's result registry saves: one per registered launcher. */
    private fun ActivityController<ComponentActivity>.launcherKeys(): Set<String> {
        val state = Bundle()
        saveInstanceState(state)
        return registeredKeys(state).also { assertTrue("no launcher registered", it.isNotEmpty()) }
    }

    private fun registeredKeys(bundle: Bundle): Set<String> = bundle.keySet().flatMap { key ->
        @Suppress("DEPRECATION")
        when (val value = bundle.get(key)) {
            is Bundle -> registeredKeys(value)
            is ArrayList<*> -> if (key == "KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS") value.filterIsInstance<String>() else emptyList()
            else -> emptyList()
        }
    }.toSet()

    @Test
    fun theChatsLaunchersKeepTheirKeysInAProcessThatAndroidRestarted() {
        harness.start()
        // The first process logged out once: its in-memory state differs from the new process's.
        Reception.logout()
        harness.mainLoopUntil { ReceptionState.chatKey != null }
        val first = TestActivities.build(ComponentActivity::class.java).setup().showChat()
        settled()
        val before = first.launcherKeys()
        val saved = Bundle().also { first.saveInstanceState(it) }
        first.pause().stop().destroy()
        // The process dies while the picker is up; Android starts a new one, which configures again.
        ReceptionState.resetForTests()
        ReceptionAPI.serviceUrl = harness.server.url("/")
        ReceptionState.configure(RuntimeEnvironment.getApplication(), ChatHarness.APP_ID)
        harness.mainLoopUntil { ReceptionState.chatKey != null }
        val restored = TestActivities.build(ComponentActivity::class.java).create(saved).showChat()
            .start().restoreInstanceState(saved).postCreate(saved).resume().visible()
        settled()
        assertEquals("the restored chat registered launchers under new keys", before, restored.launcherKeys())
    }
}
