package sh.reception

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertFalse
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
    fun olderOutgoingMessagesFadeOnlyWithoutIncreasedContrast() {
        assertTrue(fadesOutgoing(fadesOlderMessages = true, increasedContrast = false))
        assertFalse(fadesOutgoing(fadesOlderMessages = true, increasedContrast = true))
        assertFalse(fadesOutgoing(fadesOlderMessages = false, increasedContrast = false))
        assertFalse(fadesOutgoing(fadesOlderMessages = false, increasedContrast = true))
    }
}
