package sh.reception.ui

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.savedstate.SavedStateRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.reception.Log
import sh.reception.Reception
import sh.reception.testing.MainLooperDrain
import sh.reception.testing.TestActivities

/**
 * The identity lives in the host's saved-state registry, which belongs to the host: the SDK runs and removes only its
 * own provider, and a registry that fails leaves the activity without an identity, never fails the host's lifecycle.
 * The first two are the audit's probes (A27-1).
 */
@RunWith(RobolectricTestRunner::class)
class ActivityIdentityTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val key = "sh.reception.returningSheet"
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        ActivityIdentity.resetForTests()
        Log.level = Reception.LogLevel.DEBUG
        Log.handler = { _, line -> lines += line }
    }

    @After
    fun tearDown() {
        Log.handler = null
        Log.level = Reception.LogLevel.INFO
        TestActivities.closeAll()
    }

    @Test
    fun lifecycleMustNotExecuteAForeignProvider() {
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        activity.savedStateRegistry.registerSavedStateProvider(key) { error("foreign provider invoked outside save") }
        // This is called for every host on creation, even with no sheet or configuration state.
        ReturningSheet.created(activity)
        assertNull(ActivityIdentity.of(activity))
        assertEquals(listOf("Activity has no identity, its saved-state key is taken"), lines)
    }

    @Test
    fun destructionMustNotRemoveAForeignProvider() {
        val activity = TestActivities.build(ComponentActivity::class.java).setup().get()
        val provider = SavedStateRegistry.SavedStateProvider { Bundle() }
        activity.savedStateRegistry.registerSavedStateProvider(key, provider)
        ReturningSheet.created(activity)
        ReturningSheet.destroyed(activity)
        assertSame("foreign provider must remain", provider, activity.savedStateRegistry.getSavedStateProvider(key))
    }

    /** Android 10 reads a restored bundle whole on first access, so the unreadable value throws inside the registry. */
    @Test
    @Config(sdk = [29])
    fun aRestoredStateThatCannotBeReadLeavesTheActivityWithoutAnIdentity() {
        val controller = TestActivities.build(ComponentActivity::class.java).create(unreadableState())
        val activity = controller.get()
        assertNull(ActivityIdentity.of(activity))
        assertNull(ActivityIdentity.of(activity))
        // Its next saved state carries no identity, so the instance recreated from it gets a new one.
        val saved = Bundle()
        controller.saveInstanceState(saved)
        assertNull(requireNotNull(saved.getBundle(SAVED_COMPONENTS)?.getBundle(key)).getString(key))
        ActivityIdentity.forget(activity)
        assertNull(activity.savedStateRegistry.getSavedStateProvider(key))
        assertEquals(listOf("Activity has no identity, its saved state failed (IllegalStateException)"), lines)
    }

    /** A saved activity state whose identity bundle also holds a value that throws when it is read. */
    private fun unreadableState(): Bundle {
        val identity = Bundle().apply {
            putString(key, "restored")
            putParcelable("unreadable", Unreadable())
        }
        val state = Bundle().apply { putBundle(SAVED_COMPONENTS, Bundle().apply { putBundle(key, identity) }) }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(state)
            parcel.setDataPosition(0)
            return requireNotNull(parcel.readBundle(Unreadable::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        /** `SavedStateRegistry`'s key for its providers' bundles in the activity's state. */
        const val SAVED_COMPONENTS = "androidx.lifecycle.BundlableSavedStateRegistry.key"
    }
}

internal class Unreadable : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = Unit

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Unreadable> = object : Parcelable.Creator<Unreadable> {
            override fun createFromParcel(source: Parcel): Unreadable = error("unreadable")

            override fun newArray(size: Int): Array<Unreadable?> = arrayOfNulls(size)
        }
    }
}
