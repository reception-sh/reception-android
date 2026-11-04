package sh.reception.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import sh.reception.ContrastMonitor
import sh.reception.LocalAppearance
import sh.reception.LocalReceptionDarkMode
import sh.reception.LocalReceptionIncreasedContrast
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.hostBackground
import sh.reception.resolveDarkMode
import sh.reception.systemBlueDark
import sh.reception.systemBlueLight

/**
 * The root every chat draws under: one appearance snapshot, the resolved dark mode, increased contrast and the chat
 * language's layout direction. The SDK's sheet, and an embedded chat whose host has no theme of its own, also get the
 * SDK's Material theme: dynamic color on Android 12+, the iOS system blue below.
 */
@Composable
internal fun ChatRoot(embedded: Boolean, content: @Composable () -> Unit) {
    val appearance = ReceptionAppearance.resolved
    val hostBackground = hostBackground(embedded, MaterialTheme.colorScheme)
    val dark = resolveDarkMode(appearance.preferredColorScheme, hostBackground, isSystemInDarkTheme())
    val increasedContrast by ContrastMonitor.increasedContrast.collectAsState()
    val provided: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalAppearance provides appearance,
            LocalReceptionDarkMode provides dark,
            LocalReceptionIncreasedContrast provides increasedContrast,
            LocalLayoutDirection provides ReceptionLocalization.layoutDirection,
            content = content,
        )
    }
    // The host's own theme keeps its accent; otherwise the SDK's scheme supplies the sheet's default accent.
    if (hostBackground != null) provided() else MaterialTheme(colorScheme = sdkColorScheme(dark), content = provided)
}

@Composable
private fun sdkColorScheme(dark: Boolean): ColorScheme {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return if (dark) darkColorScheme(primary = Theme.systemBlueDark) else lightColorScheme(primary = Theme.systemBlueLight)
}
