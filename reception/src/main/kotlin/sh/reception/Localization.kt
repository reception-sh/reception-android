package sh.reception

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/**
 * The chat's language. The SDK resolves it itself and reads its strings through its own [Resources], so the host's
 * locale configuration never changes the matching rules.
 */
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

    private class LocalizedResources(val language: String, val resources: Resources)

    private var cache: LocalizedResources? = null

    /** The app's language as a canonical BCP 47 tag, before matching against the bundled languages. */
    fun appLanguage(context: Context, languageOverride: String?): String =
        canonical(languageOverride ?: AppLocale.languageTag(context) ?: "en")

    fun language(context: Context, languageOverride: String?): String =
        resolveLanguage(appLanguage(context, languageOverride))

    fun layoutDirection(language: String): LayoutDirection =
        if (language in setOf("ar", "he", "ur")) LayoutDirection.Rtl else LayoutDirection.Ltr

    /** The [Locale] whose resources hold [language]'s strings and formats. */
    fun locale(language: String): Locale = when (language) {
        "zh-HK" -> Locale.forLanguageTag("zh-Hant-HK")
        else -> Locale.forLanguageTag(language)
    }

    fun string(context: Context, language: String, @StringRes id: Int, vararg arguments: Any): String {
        val resources = resources(context, language)
        return if (arguments.isEmpty()) resources.getString(id) else resources.getString(id, *arguments)
    }

    fun plural(context: Context, language: String, @PluralsRes id: Int, count: Int): String =
        resources(context, language).getQuantityString(id, count, count)

    private fun resources(context: Context, language: String): Resources {
        cache?.takeIf { it.language == language }?.let { return it.resources }
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale(language))
        val resources = context.applicationContext.createConfigurationContext(configuration).resources
        cache = LocalizedResources(language, resources)
        return resources
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
