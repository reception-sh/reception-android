package sh.reception

import android.os.LocaleList
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AppLocaleTest {
    private val german = LocaleList.forLanguageTags("de-DE")

    @Test
    fun anEnglishOnlyAppStaysEnglishOnAGermanOnlyPhone() {
        assertEquals("en", AppLocale.resolve(listOf(Locale.forLanguageTag("en")), german))
        assertEquals("en-GB", AppLocale.resolve(listOf(Locale.forLanguageTag("en-GB"), Locale.forLanguageTag("fr")), german))
    }

    @Test
    fun aDeclaredSystemLocaleWinsInTheSystemOrder() {
        val system = LocaleList.forLanguageTags("de-DE,fr-FR")
        assertEquals("fr-FR", AppLocale.resolve(listOf(Locale.forLanguageTag("en"), Locale.forLanguageTag("fr")), system))
    }

    @Test
    fun withoutADeclarationTheFirstSystemLocaleIsUsed() {
        assertEquals("de-DE", AppLocale.resolve(emptyList(), german))
    }
}
