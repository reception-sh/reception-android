package sh.reception.client

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** A recorded message ID is on disk when `record` answers, so a process that crashes next still ignores the repeat. */
@RunWith(RobolectricTestRunner::class)
class PushMessageIdsTest {
    private val application = RuntimeEnvironment.getApplication()

    /**
     * The process dies right after `record` answers: whatever was only applied (written later on another thread) never
     * reaches the file. Robolectric writes applied values at once, so this context drops them instead.
     */
    private class CrashingContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = baseContext.getSharedPreferences(name, mode)
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun apply() = Unit
                    }
                }
            }
        }
    }

    /** What a new process sees: the preferences read again from their file, not the cached instance. */
    private fun freshPreferences(): SharedPreferences {
        val cache = Class.forName("android.app.ContextImpl").getDeclaredField("sSharedPrefsCache")
        cache.isAccessible = true
        cache.set(null, null)
        return application.getSharedPreferences(PushMessageIds.PREFERENCES, Context.MODE_PRIVATE)
    }

    @Test
    fun aRecordedIdIsOnDiskBeforeRecordAnswers() {
        assertTrue(PushMessageIds.record(CrashingContext(application), "0:1700000000000021%abc"))
        assertTrue("not on disk when record answered",
            freshPreferences().getString("messageIds", null).orEmpty().contains("0:1700000000000021%abc"))
        assertFalse("repeat handled again in a new process", PushMessageIds.record(application, "0:1700000000000021%abc"))
    }

    @Test
    fun aFailedWriteStillHandlesTheMessageOnce() {
        // A directory the preferences file cannot be written to: the commit fails.
        val directory = File(application.dataDir, "shared_prefs").apply { mkdirs() }
        freshPreferences()
        assertTrue(directory.setWritable(false))
        try {
            assertTrue(PushMessageIds.record(application, "0:1700000000000022%abc"))
            assertFalse(PushMessageIds.record(application, "0:1700000000000022%abc"))
        } finally {
            directory.setWritable(true)
        }
        assertFalse(freshPreferences().getString("messageIds", null).orEmpty().contains("0:1700000000000022%abc"))
    }
}
