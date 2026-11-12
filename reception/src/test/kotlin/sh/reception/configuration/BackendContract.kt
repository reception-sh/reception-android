package sh.reception.configuration

import java.io.File

/**
 * A file of the backend checkout next to this repository (`../reception`), when there is one. Tests compare their
 * checked copies of the backend's lists against it, so a change on either side fails locally.
 */
internal object BackendContract {
    fun source(path: String): String? = listOf("../../reception", "../reception").map { File(it, path) }
        .firstOrNull { it.isFile }?.readText()
}
