package sh.reception

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build

/** The host app's language: its per-app locale when set, otherwise the first system locale. */
internal object AppLocale {
    private const val APP_COMPAT_DELEGATE = "androidx.appcompat.app.AppCompatDelegate"

    fun languageTag(context: Context): String? =
        perAppLanguageTag(context) ?: Resources.getSystem().configuration.locales[0]?.toLanguageTag()

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
