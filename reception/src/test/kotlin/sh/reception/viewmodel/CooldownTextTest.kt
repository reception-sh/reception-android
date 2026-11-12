package sh.reception.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.client.RetryScope
import sh.reception.client.ServerCooldowns
import sh.reception.testing.MainLooperDrain

/** Wait texts round up in the largest unit and use each language's own phrase. Robolectric provides ICU. */
@RunWith(RobolectricTestRunner::class)
class CooldownTextTest {
    @get:Rule
    val mainLooper = MainLooperDrain()

    private val now = 1_800_000_000_000L

    private fun notice(seconds: Long, language: String) =
        CooldownText.notice(ServerCooldowns.Cooldown(RetryScope.MESSAGES, now + seconds * 1000, automatic = false), now, language)

    @Test
    fun englishWaitsRoundUpInSecondsMinutesAndHours() {
        assertEquals("Message limit reached. You can send again in 30 seconds.", notice(30, "en"))
        assertEquals("Message limit reached. You can send again in 5 minutes.", notice(5 * 60, "en"))
        assertEquals("Message limit reached. You can send again in 2 hours.", notice(2 * 3600, "en"))
        assertEquals("in 2 minutes", CooldownText.relative(now + 61_000, now, "en"))
    }

    @Test
    fun germanUsesItsOwnPhrase() {
        assertEquals("Nachrichtenlimit erreicht. Du kannst in 30 Sekunden wieder senden.", notice(30, "de"))
        assertEquals("Nachrichtenlimit erreicht. Du kannst in 5 Minuten wieder senden.", notice(5 * 60, "de"))
        assertEquals("Nachrichtenlimit erreicht. Du kannst in 2 Stunden wieder senden.", notice(2 * 3600, "de"))
        assertEquals("Wird in 8 Sekunden erneut gesendet", CooldownText.resend(now + 7_200, now, "de"))
    }
}
