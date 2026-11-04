package sh.reception.configuration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.unit.Dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import sh.reception.Reception
import sh.reception.ReceptionColor
import sh.reception.ReceptionLocalization
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The host's own appearance in the dashboard's schema (`appAppearanceSchema`), sent with device registration and
 * updates so the dashboard editor can start from what the app sets in code. It lists only values that differ from
 * `Appearance()`, so the accent only when the host set one; bundled images and font families are reported as present,
 * never sent.
 */
internal object HostAppearanceReport {
    fun payload(appearance: Reception.Appearance, usesRemoteAppearance: Boolean): JsonObject = buildJsonObject {
        put("usesRemoteAppearance", usesRemoteAppearance)
        val colors = buildJsonObject {
            pair(appearance.accentColor)?.let { put("accent", it) }
            pair(appearance.onAccentColor)?.let { put("onAccent", it) }
            pair(appearance.chatBackground)?.let { put("background", it) }
            pair(appearance.incomingBackground)?.let { put("incoming", it) }
            pair(appearance.incomingForeground)?.let { put("incomingText", it) }
        }
        if (colors.isNotEmpty()) put("colors", colors)
        val text = buildJsonObject {
            appearance.titleOverride?.let { put("title", it) }
            appearance.welcomeTitleOverride?.let { put("welcomeTitle", it) }
            appearance.welcomeTextOverride?.let { put("welcomeText", it) }
        }
        if (text.isNotEmpty()) put("text", text)
        when (appearance.preferredColorScheme) {
            Reception.ColorScheme.LIGHT -> put("colorScheme", "light")
            Reception.ColorScheme.DARK -> put("colorScheme", "dark")
            null -> Unit
        }
        if (appearance.showsCloseButton) put("showsCloseButton", true)
        if (appearance.closeIcon != Reception.CloseIcon.XMARK) put("closeIcon", appearance.closeIcon.symbol)
        if (appearance.closeImage != null) put("closeImage", true)
        appearance.fontDesign?.let { put("fontDesign", it.raw) }
        // A Compose FontFamily has no name the dashboard could preview.
        if (appearance.fontFamily != null) put("fontFamily", "custom")
        if (!appearance.fadesOlderMessages) put("fadesOlderMessages", false)
        val icon = welcomeIcon(appearance)
        if (icon.isNotEmpty()) put("welcomeIcon", icon)
        val visibility = buildJsonObject {
            if (appearance.hidesTitle) put("title", false)
            if (appearance.hidesWelcomeTitle) put("welcomeTitle", false)
            if (appearance.hidesWelcomeText) put("welcomeText", false)
            if (appearance.hidesPushStatus) put("pushStatus", false)
            if (appearance.welcomeIcon?.icon == AppearanceIcon.Hidden) put("welcomeIcon", false)
        }
        if (visibility.isNotEmpty()) put("visibility", visibility)
    }

    private fun welcomeIcon(appearance: Reception.Appearance): JsonObject = buildJsonObject {
        when (val icon = appearance.welcomeIcon?.icon) {
            is AppearanceIcon.Symbol -> put("symbol", icon.name)
            is AppearanceIcon.Local, is AppearanceIcon.Remote -> put("image", true)
            AppearanceIcon.Hidden, null -> Unit
        }
        pair(appearance.welcomeIconColor)?.let { put("color", it) }
        appearance.welcomeIconSize?.let { put("size", whole(it)) }
        if (appearance.welcomeIconCornerRadius.value != 0f) put("cornerRadius", whole(appearance.welcomeIconCornerRadius))
        val offset = appearance.welcomeIconOffset
        if (offset.x.value != 0f || offset.y.value != 0f) {
            putJsonObject("offset") {
                put("x", whole(offset.x))
                put("y", whole(offset.y))
            }
        }
    }

    private fun whole(value: Dp): Int = if (value.value.isFinite()) value.value.roundToInt() else 0

    /**
     * Light and dark variants as opaque `#RRGGBB`, the only form the dashboard accepts; a single color sends the same
     * value twice. Nothing while a color resource cannot be read yet.
     */
    private fun pair(color: ReceptionColor?): JsonObject? {
        val adaptive = color?.adaptive(ReceptionLocalization.context) ?: return null
        return pair(adaptive.light ?: return null, adaptive.dark ?: return null)
    }

    fun pair(light: Color, dark: Color = light): JsonObject = buildJsonObject {
        put("light", hex(light))
        put("dark", hex(dark))
    }

    private fun hex(color: Color): String {
        val srgb = color.convert(ColorSpaces.Srgb)
        val channels = listOf(srgb.red, srgb.green, srgb.blue).map { (it.coerceIn(0f, 1f) * 255).roundToInt() }
        return String.format(Locale.ROOT, "#%02X%02X%02X", channels[0], channels[1], channels[2])
    }
}
