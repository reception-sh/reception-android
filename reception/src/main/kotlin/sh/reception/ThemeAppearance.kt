package sh.reception

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import sh.reception.configuration.ResolvedAppearance

/**
 * The resolved appearance a chat draws with. The chat screen provides `ReceptionAppearance.resolved` at its root, so
 * every color, font and icon below it reads one snapshot instead of resolving it again.
 */
internal val LocalAppearance = compositionLocalOf { ReceptionAppearance.resolved }

/**
 * The host's accent for a null `accentColor`: the enclosing `MaterialTheme`'s primary for an embedded chat, the
 * presenting activity's theme accent (`HostAccent.color`) for the SDK's sheet. Null uses the system blue.
 */
internal val LocalHostAccent = staticCompositionLocalOf<Color?> { null }

internal val Theme.appearance: ResolvedAppearance
    @Composable @ReadOnlyComposable get() = LocalAppearance.current
