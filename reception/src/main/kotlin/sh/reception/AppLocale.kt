package sh.reception

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import sh.reception.client.Storage
import java.util.Locale

/**
 * The host app's language (SPEC.md, Language): its per-app locale when set; otherwise, when the app declares its
 * languages in `android:localeConfig` (Android 13+), the first system locale it supports, or its first declared locale
 * when none is, so an English-only app stays English on a German phone; otherwise the first system locale.
 */
internal object AppLocale {
    private const val APP_COMPAT_DELEGATE = "androidx.appcompat.app.AppCompatDelegate"
    private val legacyLanguages = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    // Strings read the language on every lookup; the answer changes only with a configuration change or a new
    // per-app locale, which `invalidate` covers.
    private var cached: String? = null

    fun languageTag(context: Context?): String? {
        if (context == null) return LocaleList.getDefault()[0]?.toLanguageTag()
        cached?.let { return it }
        val tag = perAppLanguageTag(context) ?: systemLanguageTag(context)
        cached = tag
        return tag
    }

    // The app's `localeConfig` never changes while it runs; parsing it reads the APK, so `prepare` does it off the
    // main thread. Empty: the app declares no locale configuration.
    @Volatile
    private var declared: List<Locale>? = null

    fun invalidate() {
        cached = null
    }

    /** Reads the declared locales on the storage thread, before any request needs the language. */
    fun prepare(context: Context) {
        Storage.launch { declaredLocales(context) }
    }

    private fun systemLanguageTag(context: Context): String? = resolve(declaredLocales(context), LocaleList.getDefault())

    /**
     * The first system locale the app declares; when it declares languages but none matches, its first declared one,
     * so an English-only app stays English on a German-only phone. Without a declaration, the first system locale.
     */
    fun resolve(declared: List<Locale>, system: LocaleList): String? {
        if (declared.isEmpty()) return system[0]?.toLanguageTag()
        return (declaredMatch(declared, system) ?: declared.first()).toLanguageTag()
    }

    private fun declaredLocales(context: Context): List<Locale> = declared ?: readDeclared(context).also { declared = it }

    private fun readDeclared(context: Context): List<Locale> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
        return readLocaleConfig(context)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun readLocaleConfig(context: Context): List<Locale> {
        val config = LocaleConfig(context)
        val locales = config.supportedLocales
        if (config.status != LocaleConfig.STATUS_SUCCESS || locales == null) return emptyList()
        return (0 until locales.size()).map { locales[it] }
    }

    private fun declaredMatch(supported: List<Locale>, system: LocaleList): Locale? =
        (0 until system.size()).map { system[it] }.firstOrNull { locale ->
            supported.any { matches(declared = it, system = locale) }
        }

    /** The language must match; the script and region only when the declared locale names them. */
    private fun matches(declared: Locale, system: Locale): Boolean {
        if (language(declared) != language(system)) return false
        if (declared.script.isNotEmpty() && !declared.script.equals(script(system), ignoreCase = true)) return false
        return declared.country.isEmpty() || declared.country.equals(system.country, ignoreCase = true)
    }

    private fun language(locale: Locale): String = locale.language.let { legacyLanguages[it] ?: it }

    // "zh-TW" names no script but writes Traditional Chinese; ICU's likely subtags supply it.
    private fun script(locale: Locale): String =
        locale.script.ifEmpty { ULocale.addLikelySubtags(ULocale.forLocale(locale)).script }

    private fun perAppLanguageTag(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return null
            return manager.applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
        }
        return appCompatLanguageTag()
    }

    // Below Android 13 only AppCompat knows the per-app locale. It is optional, so it is read by name.
    private fun appCompatLanguageTag(): String? = try {
        val delegate = Class.forName(APP_COMPAT_DELEGATE)
        val locales = delegate.getMethod("getApplicationLocales").invoke(null)
        val tags = locales?.javaClass?.getMethod("toLanguageTags")?.invoke(locales) as? String
        tags?.split(',')?.firstOrNull()?.takeIf { it.isNotBlank() }
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
