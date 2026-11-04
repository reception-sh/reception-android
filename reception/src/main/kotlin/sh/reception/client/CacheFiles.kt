package sh.reception.client

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The SDK's own files: in `noBackupFilesDir`, so backups never carry chat content to another device. */
internal object CacheFiles {
    fun directory(context: Context, scope: String, kind: String): File =
        File(File(context.noBackupFilesDir, "sh.reception"), "${hash(scope)}-$kind")

    fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun prepare(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.name}")
    }

    /** Replaces [file] in one step, so a crash never leaves half a snapshot. */
    fun write(file: File, data: ByteArray) {
        file.parentFile?.let(::prepare)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(data)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("cannot replace ${file.name}")
        }
    }
}
