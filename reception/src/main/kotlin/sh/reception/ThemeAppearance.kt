package sh.reception

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import sh.reception.configuration.ResolvedAppearance

/**
 * The resolved appearance a chat draws with. The chat screen provides `ReceptionAppearance.resolved` at its root, so
 * every color, font and icon below it reads one snapshot instead of resolving it again.
 */
internal val LocalAppearance = compositionLocalOf { ReceptionAppearance.resolved }

internal val Theme.appearance: ResolvedAppearance
    @Composable @ReadOnlyComposable get() = LocalAppearance.current
