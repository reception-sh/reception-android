package sh.reception

import android.annotation.SuppressLint
import android.content.Context
import android.icu.text.PluralRules
import androidx.compose.ui.unit.LayoutDirection
import sh.reception.strings.Strings
import java.util.IllegalFormatException
import java.util.Locale

/**
 * The chat's language and its built-in strings. The SDK resolves the language itself and reads its strings from the
 * generated tables in `sh.reception.strings`, so neither the host's locale configuration nor App Bundle language splits
 * change what the chat shows.
 */
// Holds only the application context, which lives as long as the process.
@SuppressLint("StaticFieldLeak")
internal object ReceptionLocalization {
    val supportedLanguages: List<String> = listOf(
        "en", "de", "fr", "fr-CA", "es", "es-419", "it", "pt-BR", "pt-PT", "ca", "ro", "nl",
        "sv", "da", "nb", "fi", "pl", "cs", "sk", "hu", "sl", "hr", "el", "uk", "ru", "tr",
        "ja", "ko", "zh-Hans", "zh-Hant", "zh-HK", "th", "vi", "id", "ms", "hi", "bn", "gu",
        "kn", "ml", "mr", "or", "pa", "ta", "te", "ur", "ar", "he",
    )

    private val tagPattern = Regex(
        "^([A-Za-z]{2,3}|[A-Za-z]{5,8})(-[A-Za-z]{4})?(-([A-Za-z]{2}|[0-9]{3}))?" +
            "(-([A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*" +
            "(-[0-9A-WY-Za-wy-z](-[A-Za-z0-9]{2,8})+)*(-[xX](-[A-Za-z0-9]{1,8})+)?$",
    )

    // The backend uses the modern codes; Java and some Android versions still return the retired ones.
    private val legacyLanguages = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "mo" to "ro")

    /** The application context, set by `configure`; before it only the system locale is known. */
    var context: Context? = null

    /** `Reception.languageOverride`. */
    var languageOverride: String? = null

    /** The app's language as a canonical BCP 47 tag, before matching against the bundled languages. */
    val appLanguage: String
        get() = canonical(languageOverride ?: context?.let(AppLocale::languageTag) ?: "en")

    val language: String
        get() = resolveLanguage(appLanguage)

    val locale: Locale
        get() = locale(language)

    val layoutDirection: LayoutDirection
        get() = layoutDirection(language)

    fun layoutDirection(language: String): LayoutDirection =
        if (language in setOf("ar", "he", "ur")) LayoutDirection.Rtl else LayoutDirection.Ltr

    /** The [Locale] that formats [language]'s numbers and selects its plural forms. */
    fun locale(language: String): Locale = when (language) {
        "zh-HK" -> Locale.forLanguageTag("zh-Hant-HK")
        else -> Locale.forLanguageTag(language)
    }

    /** The built-in string [key] in the chat's language, English when that language lacks it. */
    fun string(key: String, vararg arguments: Any): String = string(language, key, *arguments)

    fun string(language: String, key: String, vararg arguments: Any): String {
        val text = Strings.languages[language]?.strings?.get(key) ?: Strings.languages["en"]?.strings?.get(key) ?: key
        return format(locale(language), text, arguments)
    }

    /** The plural form of [key] for [count], selected by the CLDR rules of the chat's language. */
    fun plural(key: String, count: Int): String = plural(language, key, count)

    fun plural(language: String, key: String, count: Int): String {
        val locale = locale(language)
        val forms = Strings.languages[language]?.plurals?.get(key) ?: Strings.languages["en"]?.plurals?.get(key)
            ?: return count.toString()
        val category = PluralRules.forLocale(locale).select(count.toDouble())
        val text = forms[category] ?: forms["other"] ?: return count.toString()
        return format(locale, text, arrayOf(count))
    }

    // The tables are generated from reviewed catalogs; a broken placeholder shows its text rather than crashing.
    private fun format(locale: Locale, text: String, arguments: Array<out Any>): String {
        if (arguments.isEmpty()) return text
        return try {
            String.format(locale, text, *arguments)
        } catch (_: IllegalFormatException) {
            text
        }
    }

    fun canonical(identifier: String): String {
        val value = identifier.trim().replace('_', '-')
        if (!tagPattern.matches(value)) return "en"
        val variants = mutableSetOf<String>()
        val extensions = mutableSetOf<String>()
        var inExtension = false
        for (part in value.lowercase(Locale.ROOT).split('-').drop(1)) {
            if (part == "x") break
            if (part.length == 1) {
                if (!extensions.add(part)) return "en"
                inExtension = true
            } else if (!inExtension && (part.length >= 5 || (part.length == 4 && part.first().isDigit()))) {
                if (!variants.add(part)) return "en"
            }
        }
        val components = Locale.forLanguageTag(value).toLanguageTag().split('-').toMutableList()
        components[0] = legacyLanguages[components[0]] ?: components[0]
        val base = components.takeWhile { it.length != 1 }.joinToString("-")
        return if (base.isEmpty() || base.length > 35) "en" else base
    }

    fun resolveLanguage(identifier: String): String {
        val tag = canonical(identifier)
        supportedLanguages.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
        val parts = tag.split('-')
        val language = parts.first().lowercase(Locale.ROOT)
        val subtags = parts.drop(1)
        val script = subtags.firstOrNull { it.length == 4 }?.lowercase(Locale.ROOT)
        val region = subtags.firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
            ?.uppercase(Locale.ROOT)
        return when (language) {
            "zh" -> when {
                script == "hans" -> "zh-Hans"
                region == "HK" || region == "MO" -> "zh-HK"
                script == "hant" || region == "TW" -> "zh-Hant"
                else -> "zh-Hans"
            }
            "es" -> if (region == null || region == "ES") "es" else "es-419"
            "pt" -> if (region == "PT") "pt-PT" else "pt-BR"
            "fr" -> if (region == "CA") "fr-CA" else "fr"
            "no", "nn", "nb" -> "nb"
            else -> if (language in supportedLanguages) language else "en"
        }
    }
}
