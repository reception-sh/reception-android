package sh.reception

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// The iOS system colors (elevated sheet variants in dark mode), following the system color scheme.

@Composable
@ReadOnlyComposable
private fun adaptive(light: Color, dark: Color): Color = if (isSystemInDarkTheme()) dark else light

internal val Theme.success: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color(0xFF34C759), Color(0xFF30D158))

internal val Theme.error: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color(0xFFFF3B30), Color(0xFFFF453A))

internal val Theme.foreground: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color.Black, Color.White)

internal val Theme.muted: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color(0x993C3C43), Color(0x99EBEBF5))

internal val Theme.tertiary: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color(0x4D3C3C43), Color(0x4DEBEBF5))

internal val Theme.separator: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color(0x4A3C3C43), Color(0x99545458))

internal val Theme.dismissFill: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color(0x29787880), Color(0x52787880))

internal val Theme.imagePlaceholder: Color
    @Composable @ReadOnlyComposable get() = dismissFill

internal val Theme.imageBorder: Color
    @Composable @ReadOnlyComposable get() = foreground.copy(alpha = 0.12f)

internal val Theme.composerDisabled: Color
    @Composable @ReadOnlyComposable get() = muted.copy(alpha = muted.alpha * 0.2f)

internal val Theme.background: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color.White, Color(0xFF1C1C1E))

internal val Theme.secondary: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color(0xFFF2F2F7), Color(0xFF2C2C2E))

internal val Theme.incomingForeground: Color
    @Composable @ReadOnlyComposable get() = foreground

internal val Theme.accent: Color
    @Composable @ReadOnlyComposable get() = adaptive(Color(0xFF007AFF), Color(0xFF0A84FF))

internal val Theme.outgoingText: Color
    get() = Color.White

internal val Theme.outgoingForeground: Color
    get() = outgoingText

internal val Theme.lightboxBackground: Color
    get() = Color.Black

internal val Theme.lightboxForeground: Color
    get() = Color.White

internal val Theme.jumpToLatestShadow: Color
    get() = Color.Black.copy(alpha = 0.06f)

/** Older outgoing bubbles soften toward white; only the fill is tinted, never their text or photos. */
internal fun Theme.outgoingBackground(accent: Color, olderBy: Int, fades: Boolean, increasedContrast: Boolean): Color {
    if (!fades || olderBy <= 0 || increasedContrast) return accent
    val fade = minOf(OUTGOING_MAXIMUM_FADE, olderBy * OUTGOING_FADE_STEP)
    return Color(
        red = accent.red * (1 - fade) + fade,
        green = accent.green * (1 - fade) + fade,
        blue = accent.blue * (1 - fade) + fade,
        alpha = accent.alpha,
    )
}
