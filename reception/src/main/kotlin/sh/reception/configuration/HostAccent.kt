package sh.reception.configuration

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

/**
 * The app's accent when `Appearance.accentColor` is null and no enclosing `MaterialTheme` supplies one: the theme's
 * `colorPrimary`, else its `colorAccent`. AppCompat and Material Components themes set the platform attributes too.
 * A theme without either (only pre-Material themes) uses [fallback], the iOS system blue the dashboard also shows.
 */
internal object HostAccent {
    val fallback: Pair<Color, Color> = Color(0xFF007AFF) to Color(0xFF0A84FF)

    /** The accent of [context]'s theme, for example the activity the chat presents over. */
    fun color(context: Context): Color? =
        attribute(context, android.R.attr.colorPrimary) ?: attribute(context, android.R.attr.colorAccent)

    /** The application theme's accent in light and dark mode, for the dashboard's report. */
    fun pair(context: Context): Pair<Color, Color>? {
        val light = color(themed(context, Configuration.UI_MODE_NIGHT_NO)) ?: return null
        val dark = color(themed(context, Configuration.UI_MODE_NIGHT_YES)) ?: return null
        return light to dark
    }

    private fun themed(context: Context, night: Int): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return ContextThemeWrapper(context.createConfigurationContext(configuration), context.applicationInfo.theme)
    }

    private fun attribute(context: Context, attribute: Int): Color? {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return null
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return Color(value.data)
        if (value.resourceId == 0) return null
        return try {
            Color(ContextCompat.getColor(context, value.resourceId))
        } catch (_: Resources.NotFoundException) {
            null
        }
    }
}
