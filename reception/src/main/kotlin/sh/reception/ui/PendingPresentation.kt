package sh.reception.ui

/**
 * A presentation whose record is being saved: requests that arrive meanwhile join it instead of being dropped as
 * "already presented". It starts when any joined request still wants it, so a host's own `openChat()` keeps a tap's
 * presentation alive after routing turned off, and every joined request learns the outcome. Main thread only.
 */
internal class PendingPresentation(val token: String, stillWanted: () -> Boolean, completed: (Boolean) -> Unit) {
    private val wanted = mutableListOf(stillWanted)
    private val completions = mutableListOf(completed)

    fun join(stillWanted: () -> Boolean, completed: (Boolean) -> Unit) {
        wanted += stillWanted
        completions += completed
    }

    /** Asks every joined request (each one's check runs, like a lone request's). */
    fun stillWanted(): Boolean = wanted.map { it() }.any { it }

    fun finish(started: Boolean) = completions.forEach { it(started) }
}
