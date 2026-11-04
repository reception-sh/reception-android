package sh.reception

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

// Text styles of the iOS chat. Sizes are sp, so they follow the font scale like Dynamic Type, custom families included.

/** The app's font family, unless the dashboard published a font design; otherwise the system face of the design. */
internal val Theme.fontFamily: FontFamily
    @Composable @ReadOnlyComposable get() = appearance.fontFamily ?: when (appearance.fontDesign) {
        Reception.FontDesign.SERIF -> FontFamily.Serif
        Reception.FontDesign.MONOSPACED -> FontFamily.Monospace
        // Android has no rounded system face.
        Reception.FontDesign.DEFAULT, Reception.FontDesign.ROUNDED, null -> FontFamily.Default
    }

@Composable
@ReadOnlyComposable
internal fun Theme.textStyle(size: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(fontFamily = fontFamily, fontSize = size, fontWeight = weight)

internal val Theme.titleFont: TextStyle
    @Composable @ReadOnlyComposable get() = textStyle(titleSize, FontWeight.SemiBold)

internal val Theme.bodyFont: TextStyle
    @Composable @ReadOnlyComposable get() = textStyle(bodySize)

internal val Theme.captionFont: TextStyle
    @Composable @ReadOnlyComposable get() = textStyle(captionSize)

internal val Theme.timestampFont: TextStyle
    @Composable @ReadOnlyComposable get() = textStyle(timestampSize)

internal val Theme.footnoteFont: TextStyle
    @Composable @ReadOnlyComposable get() = textStyle(footnoteSize)

internal val Theme.subheadlineFont: TextStyle
    @Composable @ReadOnlyComposable get() = textStyle(subheadlineSize)

/** iOS draws these two in the system font unless the app set a family; a published design does not apply. */
internal val Theme.dismissFont: TextStyle
    @Composable @ReadOnlyComposable get() =
        TextStyle(fontFamily = appearance.fontFamily ?: FontFamily.Default, fontSize = dismissSize, fontWeight = FontWeight.SemiBold)

internal val Theme.closeFont: TextStyle
    @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = appearance.fontFamily ?: FontFamily.Default, fontSize = closeSize)
