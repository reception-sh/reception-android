package sh.reception.viewmodel

import android.icu.text.DisplayContext
import android.icu.text.NumberFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import sh.reception.ReceptionLocalization
import sh.reception.client.RetryScope
import sh.reception.client.ServerCooldowns
import sh.reception.strings.Strings
import kotlin.math.ceil

/** Wait texts in the chat language. The expiry only permits another attempt, so no text promises delivery. */
internal object CooldownText {
    fun notice(cooldown: ServerCooldowns.Cooldown, now: Long, language: String = ReceptionLocalization.language): String {
        val wait = relative(cooldown.until, now, language)
        val key = when (cooldown.scope) {
            RetryScope.MESSAGES -> Strings.messageLimitReachedYouCanSendAgain
            RetryScope.UPLOADS -> Strings.photosCanBeSentAgainYouCanStill
            RetryScope.REQUESTS -> Strings.tooManyAttemptsTryAgain
            RetryScope.SESSION, RetryScope.STREAM -> Strings.cantConnectRightNowTryAgain
        }
        return ReceptionLocalization.string(language, key, wait)
    }

    fun resend(until: Long, now: Long, language: String = ReceptionLocalization.language): String =
        ReceptionLocalization.string(language, Strings.sendingAgain, relative(until, now, language))

    /**
     * "in 8 seconds", "in 13 minutes", "in 2 hours": rounded up in the largest unit, so the wait is never understated.
     * ICU supplies each language's own grammar for the phrase.
     */
    fun relative(until: Long, now: Long, language: String = ReceptionLocalization.language): String {
        val seconds = maxOf(1.0, ceil((until - now) / 1000.0))
        val (unit, size) = when {
            seconds < 60 -> RelativeDateTimeFormatter.RelativeUnit.SECONDS to 1.0
            seconds < 3600 -> RelativeDateTimeFormatter.RelativeUnit.MINUTES to 60.0
            else -> RelativeDateTimeFormatter.RelativeUnit.HOURS to 3600.0
        }
        val locale = ULocale.forLocale(ReceptionLocalization.locale(language))
        val formatter = RelativeDateTimeFormatter.getInstance(locale, NumberFormat.getInstance(locale),
            RelativeDateTimeFormatter.Style.LONG, DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE)
        return formatter.format(ceil(seconds / size), RelativeDateTimeFormatter.Direction.NEXT, unit)
    }
}
