package sh.reception.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.reception.ReceptionLocalization
import sh.reception.client.ImagePreparation
import sh.reception.client.ReceptionAPIError
import sh.reception.strings.Strings
import java.io.IOException
import java.util.UUID

/**
 * The message being written: its text (persisted as the draft) and up to five prepared photos (memory only). Picked
 * photos are prepared in the chat's [scope], so a configuration change while they prepare loses none of them.
 */
internal class ComposerModel(private val scope: CoroutineScope) {
    /** A picked photo as it will be sent, with a small decoded preview for the composer. */
    class Photo(val data: ByteArray, val preview: Bitmap) {
        val id: String = UUID.randomUUID().toString()
    }

    private var currentText by mutableStateOf("")
    var text: String
        get() = currentText
        set(value) {
            currentText = value
            isEdited = true
            onTextChange?.invoke(value)
        }

    /** Something was written here; the stored draft then no longer replaces the text. */
    var isEdited: Boolean = false
        private set
    var onTextChange: ((String) -> Unit)? = null
    var photos: List<Photo> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var hasPhotoError: Boolean by mutableStateOf(false)

    /** The launcher that has the system photo picker open, from its launch until its result or its disposal. */
    val picker = PhotoPickerOwnership()

    /**
     * The system photo picker is open over the chat. The chat counts as visible meanwhile, as under the iOS in-app
     * picker sheet, so the hosting activity's pause changes nothing; the app going to the background still does.
     */
    val isPicking: Boolean
        get() = picker.isPicking
    val error: String?
        get() = if (hasPhotoError) ReceptionLocalization.string(Strings.couldNotLoadThisPhoto) else null
    val canSend: Boolean
        get() = !loading && photos.size <= MAXIMUM_PHOTOS && text.length <= MAXIMUM_TEXT_LENGTH &&
            (text.isNotBlank() || photos.isNotEmpty())

    /** How many more photos the picker may return. */
    val remainingPhotos: Int
        get() = maxOf(0, MAXIMUM_PHOTOS - photos.size)

    /** The stored draft, without reporting it as typing; text written before it loaded wins. */
    fun restore(draft: String) {
        if (!isEdited) currentText = draft
    }

    /** Picked photos not prepared yet, in order; each stays here until its preparation finished. */
    private val picked = mutableListOf<Uri>()
    private var preparation: Job? = null

    /**
     * Reads and prepares picked photos off the main thread with the application context; one that cannot be read
     * shows the photo error. A pick while others prepare joins the queue.
     */
    fun add(context: Context, uris: List<Uri>) {
        picked += uris
        if (preparation != null || picked.isEmpty()) return
        val application = context.applicationContext
        loading = true
        preparation = scope.launch {
            try {
                while (picked.isNotEmpty() && remainingPhotos > 0) {
                    val photo = withContext(Dispatchers.IO) { prepare(application, picked.first()) }
                    picked.removeAt(0)
                    if (photo == null) hasPhotoError = true else photos = photos + photo
                }
                picked.clear()
            } finally {
                loading = false
                preparation = null
            }
        }
    }

    fun remove(id: String) {
        photos = photos.filterNot { it.id == id }
    }

    fun send(chat: ChatModel) {
        // During a cooldown the draft and its photos stay here untouched.
        if (!canSend || !chat.canSend || !chat.cooldownAllowsSend(photos = photos.isNotEmpty())) return
        chat.send(text, photos.map { it.data })
        text = ""
        photos = emptyList()
        hasPhotoError = false
    }

    /** A message the service refused before posting returns here, ahead of what was written since. */
    fun restoreBlocked(messageText: String, images: List<ByteArray>) {
        text = listOf(messageText, text).filter { it.isNotEmpty() }.joinToString("\n")
        photos = images.mapNotNull { data -> MessagePhotos.preview(data)?.let { Photo(data, it) } } + photos
        hasPhotoError = false
    }

    private fun prepare(context: Context, uri: Uri): Photo? = try {
        val source = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        source?.let(ImagePreparation::prepare)?.let { prepared ->
            MessagePhotos.preview(prepared.data)?.let { Photo(prepared.data, it) }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: ReceptionAPIError) {
        null
    }
}
