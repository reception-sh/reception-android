package sh.reception.configuration

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/** A published color: optional opaque `#RRGGBB` variants for light and dark mode. */
@Serializable
internal class AdaptiveAppearanceColor(val light: String? = null, val dark: String? = null) {
    /** Null when neither variant is valid; a missing or invalid variant keeps [fallback]'s color for that mode. */
    fun color(fallback: AdaptiveColor): AdaptiveColor? {
        val lightColor = light?.let(::parse)
        val darkColor = dark?.let(::parse)
        if (lightColor == null && darkColor == null) return null
        return AdaptiveColor(lightColor ?: fallback.light, darkColor ?: fallback.dark)
    }

    private companion object {
        val hex = Regex("^#[0-9A-Fa-f]{6}$")

        fun parse(value: String): Color? {
            if (!hex.matches(value)) return null
            return Color(0xFF000000 or value.substring(1).toLong(16))
        }
    }
}

/** A resolved color per mode; a null variant means the native default for that mode, chosen where it is drawn. */
internal class AdaptiveColor(val light: Color?, val dark: Color?) {
    fun resolve(dark: Boolean): Color? = if (dark) this.dark else light

    companion object {
        val unset: AdaptiveColor = AdaptiveColor(null, null)

        /** The app's color, the same in both modes; null keeps the native default. */
        fun of(color: Color?): AdaptiveColor = AdaptiveColor(color, color)
    }
}
