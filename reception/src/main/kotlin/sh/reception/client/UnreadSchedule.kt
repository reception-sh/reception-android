package sh.reception.client

import kotlinx.serialization.Serializable

@Serializable
internal data class UnreadStage(val forMinutes: Int, val everySeconds: Int)

internal object UnreadSchedule {
    val defaultStages: List<UnreadStage> = listOf(
        UnreadStage(forMinutes = 1, everySeconds = 15),
        UnreadStage(forMinutes = 10, everySeconds = 60),
        UnreadStage(forMinutes = 1440, everySeconds = 600),
    )

    /** Milliseconds until the next unread check, or null when there is none. */
    fun interval(status: String?, lastActivity: Long?, push: Boolean?, stages: List<UnreadStage>?, now: Long): Long? {
        if (status != "OPEN" || push != false || lastActivity == null) return null
        val age = now - lastActivity
        val stage = (stages ?: defaultStages).firstOrNull { age < it.forMinutes * 60_000L } ?: return null
        return if (stage.everySeconds > 0) stage.everySeconds * 1000L else null
    }
}
