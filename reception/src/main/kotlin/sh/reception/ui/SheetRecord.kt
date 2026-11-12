package sh.reception.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.reception.Log
import sh.reception.client.PresentationRecord
import sh.reception.client.Storage

/**
 * The presenter's writes of [PresentationRecord], on the storage thread in order with the SDK's other writes. Main
 * thread only.
 */
internal object SheetRecord {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Commits [entry], then runs [then] on the main thread, so a sheet started from it is on disk before it can appear
     * and a process that dies with it on screen restores it. A failed commit is logged and [then] still runs: the sheet
     * then only misses its restoration.
     */
    fun save(context: Context, entry: PresentationRecord.Entry, then: () -> Unit) {
        val application = context.applicationContext
        scope.launch {
            val saved = Storage.run { PresentationRecord.save(application, entry) }
            if (!saved) Log.error { "Chat presentation record not saved, the chat is not restored if the app is ended" }
            then()
        }
    }

    fun clear(context: Context) {
        val application = context.applicationContext
        Storage.launch { PresentationRecord.clear(application) }
    }
}
