package sh.reception.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** ISO-8601 UTC timestamps of the service as epoch milliseconds; java.time needs API 26. */
internal object Timestamp : KSerializer<Long> {
    private val pattern = Regex(
        """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$""",
    )

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("sh.reception.Timestamp", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        val value = decoder.decodeString()
        return parse(value) ?: throw SerializationException("invalid_date")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(format(value))
    }

    fun parse(value: String): Long? {
        val match = pattern.matchEntire(value) ?: return null
        val parts = match.groupValues
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        calendar.isLenient = false
        calendar.clear()
        calendar.set(parts[1].toInt(), parts[2].toInt() - 1, parts[3].toInt(), parts[4].toInt(), parts[5].toInt(),
            parts[6].toInt())
        val milliseconds = parts[7].drop(1).padEnd(3, '0').take(3).ifEmpty { "0" }.toInt()
        val offset = parts[8].takeIf { it != "Z" }?.let { zone ->
            val minutes = zone.substring(1, 3).toInt() * 60 + zone.substring(4, 6).toInt()
            if (zone[0] == '-') -minutes else minutes
        } ?: 0
        return try {
            calendar.timeInMillis + milliseconds - offset * 60_000L
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun format(value: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(value))
    }
}
