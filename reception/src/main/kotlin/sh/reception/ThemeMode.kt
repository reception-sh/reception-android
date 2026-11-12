package sh.reception

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Whether the chat draws dark, resolved once by the chat root with [receptionDarkMode]. Null outside a chat root, where
 * [Theme.isDark] resolves it like an embedded chat.
 */
internal val LocalReceptionDarkMode = compositionLocalOf<Boolean?> { null }

/** Whether the system asks for increased contrast; the chat root provides `ContrastMonitor.increasedContrast`. */
internal val LocalReceptionIncreasedContrast = compositionLocalOf { false }

/**
 * The chat's mode: the appearance's color scheme when set (a remote "system" clears only that override), else the
 * enclosing host `MaterialTheme` for an embedded chat (its background decides, because Material 3 color schemes carry
 * no dark flag), else the system setting: for the SDK's own sheet and for an embedded chat without a host theme.
 */
internal fun resolveDarkMode(preferred: Reception.ColorScheme?, hostBackground: Color?, systemDark: Boolean): Boolean =
    when (preferred) {
        Reception.ColorScheme.LIGHT -> false
        Reception.ColorScheme.DARK -> true
        null -> hostBackground?.let { it.luminance() < 0.5f } ?: systemDark
    }

/** [resolveDarkMode] for a chat root; [embedded] when it sits in the host's composition rather than the SDK's sheet. */
@Composable
@ReadOnlyComposable
internal fun receptionDarkMode(embedded: Boolean): Boolean = resolveDarkMode(
    Theme.appearance.preferredColorScheme,
    hostBackground(embedded, MaterialTheme.colorScheme),
    isSystemInDarkTheme(),
)

/**
 * The background of the host theme an embedded chat follows; null for the SDK's sheet and for an embedded chat under
 * Material 3's baseline scheme, which is what a host without a theme of its own gets (SPEC.md, Appearance).
 */
internal fun hostBackground(embedded: Boolean, scheme: ColorScheme): Color? =
    scheme.background.takeIf { embedded && !scheme.isBaseline() }

private val baselineScheme = lightColorScheme()

/** Equal to `lightColorScheme()` in every color role; Material 3's `ColorScheme` has no `equals` of its own. */
internal fun ColorScheme.isBaseline(): Boolean = roles(this) == roles(baselineScheme)

private fun roles(scheme: ColorScheme): List<Color> = with(scheme) {
    listOf(
        primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary, secondary, onSecondary,
        secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
        background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceTint, inverseSurface,
        inverseOnSurface, error, onError, errorContainer, onErrorContainer, outline, outlineVariant, scrim,
        surfaceBright, surfaceDim, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, surfaceContainerLow,
        surfaceContainerLowest,
    )
}

internal val Theme.isDark: Boolean
    @Composable @ReadOnlyComposable get() = LocalReceptionDarkMode.current ?: receptionDarkMode(embedded = true)

/** iOS skips the fade of older outgoing bubbles under Increase Contrast; Android 14+ reports the same preference. */
internal fun fadesOutgoing(fadesOlderMessages: Boolean, increasedContrast: Boolean): Boolean =
    fadesOlderMessages && !increasedContrast
