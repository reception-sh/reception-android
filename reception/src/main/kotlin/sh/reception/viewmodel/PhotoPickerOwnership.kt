package sh.reception.viewmodel

/**
 * Which photo picker launcher has the system picker open over the chat. Ownership ends with the launcher's result or
 * with the launcher itself, so a chat removed while its picker is open leaves no surviving model marked as picking.
 */
internal class PhotoPickerOwnership {
    private var owner: Any? = null

    val isPicking: Boolean
        get() = owner != null

    fun launched(by: Any) {
        owner = by
    }

    /** The picker's result arrived; a result for the recreated launcher may arrive before it is bound. */
    fun finished(by: Any) {
        if (owner === by || owner === Recreating) owner = null
    }

    /** The launcher goes away. On a configuration change the open picker passes to the recreated launcher. */
    fun released(by: Any, recreating: Boolean) {
        if (owner === by) owner = if (recreating) Recreating else null
    }

    /** A launcher appears; after a configuration change it takes over the picker its predecessor opened. */
    fun bound(to: Any) {
        if (owner === Recreating) owner = to
    }

    private object Recreating
}
