package sh.reception.client

import sh.reception.model.Attachment
import java.lang.ref.WeakReference

/** The live image loaders of one session by attachment ID; views own the loaders, so there is no second cache. */
internal class AttachmentImages {
    private val entries = mutableMapOf<String, WeakReference<AttachmentImageLoader>>()

    fun loader(attachment: Attachment, session: DeviceSession): AttachmentImageLoader {
        entries.values.removeAll { it.get() == null }
        entries[attachment.id]?.get()?.let { return it }
        return AttachmentImageLoader(attachment, session).also { entries[attachment.id] = WeakReference(it) }
    }

    fun invalidate() {
        entries.values.forEach { it.get()?.invalidate() }
        entries.clear()
    }
}
