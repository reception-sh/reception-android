package sh.reception

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.ColorRes
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import sh.reception.configuration.AdaptiveColor

/**
 * A color of the chat's appearance that can differ in light and dark mode, like an iOS dynamic color: one color for
 * both modes, a light and dark pair, or a color resource whose night-qualified variant (`values-night`) is the dark one.
 * The chat picks the variant of the mode it draws in, so `preferredColorScheme` applies to it too.
 */
public class ReceptionColor private constructor(
    private val light: Color?,
    private val dark: Color?,
    @ColorRes private val resource: Int?,
) {
    /** [color] in both modes. */
    public constructor(color: Color) : this(color, color, null)

    /** [light] in light mode, [dark] in dark mode. */
    public constructor(light: Color, dark: Color) : this(light, dark, null)

    // A resource's variants never change while the app runs; read once, the first time the chat or a report needs them.
    @Volatile
    private var variants: AdaptiveColor? = null

    /** Both variants; a resource needs the application context, so before `configure` it has none yet. */
    internal fun adaptive(context: Context?): AdaptiveColor {
        val resource = resource ?: return AdaptiveColor(light, dark)
        variants?.let { return it }
        context ?: return AdaptiveColor.unset
        return AdaptiveColor(read(context, resource, Configuration.UI_MODE_NIGHT_NO),
            read(context, resource, Configuration.UI_MODE_NIGHT_YES)).also { variants = it }
    }

    private fun read(context: Context, @ColorRes id: Int, night: Int): Color? {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        }
        return try {
            Color(ContextCompat.getColor(context.createConfigurationContext(configuration), id))
        } catch (_: Resources.NotFoundException) {
            Log.error { "ReceptionColor.resource names no color resource, the default color is used" }
            null
        }
    }

    public companion object {
        /** For Java hosts, which cannot pass a Compose `Color`: ARGB ints such as `0xFF5B4FE0`; [dark] defaults to [light]. */
        @JvmStatic
        @JvmOverloads
        public fun argb(light: Int, dark: Int = light): ReceptionColor = ReceptionColor(Color(light), Color(dark))

        /** A color resource such as `R.color.brand`; a `values-night` variant is used in dark mode. */
        @JvmStatic
        public fun resource(@ColorRes id: Int): ReceptionColor = ReceptionColor(null, null, id)
    }
}
