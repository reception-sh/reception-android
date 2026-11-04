package sh.reception

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    private val dark = darkColorScheme().background
    private val light = lightColorScheme().background

    @Test
    fun anExplicitColorSchemeWins() {
        assertFalse(resolveDarkMode(Reception.ColorScheme.LIGHT, hostBackground = dark, systemDark = true))
        assertTrue(resolveDarkMode(Reception.ColorScheme.DARK, hostBackground = light, systemDark = false))
    }

    @Test
    fun anEmbeddedChatFollowsTheHostMaterialTheme() {
        assertTrue(resolveDarkMode(null, hostBackground = dark, systemDark = false))
        assertFalse(resolveDarkMode(null, hostBackground = light, systemDark = true))
    }

    @Test
    fun theSdksSheetFollowsTheSystem() {
        assertTrue(resolveDarkMode(null, hostBackground = null, systemDark = true))
        assertFalse(resolveDarkMode(null, hostBackground = null, systemDark = false))
    }

    @Test
    fun anEmbeddedChatUnderTheBaselineSchemeCountsAsUnthemed() {
        // Nothing to follow: the system decides the mode and ChatRoot supplies the SDK's scheme and accent.
        assertNull(hostBackground(embedded = true, lightColorScheme()))
        val hostLight = lightColorScheme(primary = Color(0xFF00696B), background = Color(0xFFF4FBFA))
        assertEquals(Color(0xFFF4FBFA), hostBackground(embedded = true, hostLight))
        assertFalse(resolveDarkMode(null, hostBackground(embedded = true, hostLight), systemDark = true))
        assertTrue(resolveDarkMode(null, hostBackground(embedded = true, lightColorScheme()), systemDark = true))
    }

    @Test
    fun anyChangedColorRoleMakesTheSchemeTheHosts() {
        assertTrue(lightColorScheme().isBaseline())
        // Equal background, primary, surface and secondary, yet the host chose its own theme.
        assertFalse(lightColorScheme(surfaceContainerLowest = Color(0xFFFAFAFA)).isBaseline())
        assertFalse(lightColorScheme(tertiary = Color(0xFF7D5260).copy(alpha = 0.5f)).isBaseline())
        assertFalse(darkColorScheme().isBaseline())
    }

    @Test
    fun theSdksSheetNeverFollowsTheEnclosingScheme() {
        assertNull(hostBackground(embedded = false, darkColorScheme()))
    }

    @Test
    fun olderOutgoingMessagesFadeOnlyWithoutIncreasedContrast() {
        assertTrue(fadesOutgoing(fadesOlderMessages = true, increasedContrast = false))
        assertFalse(fadesOutgoing(fadesOlderMessages = true, increasedContrast = true))
        assertFalse(fadesOutgoing(fadesOlderMessages = false, increasedContrast = false))
        assertFalse(fadesOutgoing(fadesOlderMessages = false, increasedContrast = true))
    }
}
