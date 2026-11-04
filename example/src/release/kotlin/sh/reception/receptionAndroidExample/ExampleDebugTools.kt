package sh.reception.receptionAndroidExample

import android.app.Activity
import androidx.compose.runtime.Composable

/** Release builds: the debug tools (src/debug) are left out. */
object ExampleDebugTools {
    @Suppress("UNUSED_PARAMETER")
    fun applyWindowMode(activity: Activity) = Unit
}

@Composable
fun DebugTools() = Unit
