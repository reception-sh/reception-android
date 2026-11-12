package sh.reception.receptionAndroidExample

import android.content.Intent
import android.util.Log

/** Debug builds only: one line per intent an activity receives, for the returning-sheet evidence. */
object IntentLog {
    private const val TAG = "ReceptionExample"

    private val flagNames = listOf(
        Intent.FLAG_ACTIVITY_NEW_TASK to "NEW_TASK",
        Intent.FLAG_ACTIVITY_CLEAR_TOP to "CLEAR_TOP",
        Intent.FLAG_ACTIVITY_SINGLE_TOP to "SINGLE_TOP",
        Intent.FLAG_ACTIVITY_CLEAR_TASK to "CLEAR_TASK",
        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED to "RESET_TASK_IF_NEEDED",
        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT to "BROUGHT_TO_FRONT",
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT to "REORDER_TO_FRONT",
        Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY to "LAUNCHED_FROM_HISTORY",
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK to "MULTIPLE_TASK",
        Intent.FLAG_ACTIVITY_NO_HISTORY to "NO_HISTORY",
        Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP to "PREVIOUS_IS_TOP",
    )

    fun log(where: String, intent: Intent) {
        val flags = flagNames.filter { (flag, _) -> intent.flags and flag != 0 }.joinToString("|") { it.second }
        Log.i(
            TAG,
            "Intent $where: action=${intent.action} categories=${intent.categories} data=${intent.data} " +
                "flags=0x${Integer.toHexString(intent.flags)} [$flags] extras=${intent.extras?.keySet()} " +
                "sourceBounds=${intent.sourceBounds != null}",
        )
    }
}
