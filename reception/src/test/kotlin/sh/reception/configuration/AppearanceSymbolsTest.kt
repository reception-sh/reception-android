package sh.reception.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.reception.Reception

/** Every symbol the dashboard offers has a drawable; the checked list follows `src/lib/appearance-symbols.ts`. */
class AppearanceSymbolsTest {
    private val dashboardSymbols = listOf(
        "bubble.left.and.bubble.right", "bubble.left.fill", "message.fill", "questionmark.bubble", "envelope.fill",
        "lifepreserver", "sparkles", "heart.fill", "hand.wave.fill", "person.2.fill", "star.fill", "gift.fill",
        "hand.thumbsup.fill", "crown.fill", "tag.fill", "percent", "bolt.fill", "link", "chevron.right",
        "arrow.up.right", "arrow.right",
    )
    private val builtIns = listOf("xmark", "chevron.down", "arrow.down", "bell.fill", "photo.on.rectangle", "arrow.up",
        "exclamationmark.circle", "exclamationmark.circle.fill", "xmark.circle.fill", "clock", "wifi", "wifi.slash", "pause.circle")

    @Test
    fun everyDashboardAndBuiltInSymbolHasADrawable() {
        val missing = (dashboardSymbols + builtIns + Reception.CloseIcon.entries.map { it.symbol }).filter { AppearanceSymbols.drawable(it) == null }
        assertTrue("missing drawables: $missing", missing.isEmpty())
    }

    @Test
    fun theCheckedListMatchesTheDashboard() {
        val source = BackendContract.source("src/lib/appearance-symbols.ts") ?: return
        val arrays = Regex("""export const (?:welcome|card|accessory)Symbols = \[([^\]]*)\]""").findAll(source)
            .flatMap { match -> Regex("\"([^\"]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] } }
        val defaults = Regex("""export const defaultSymbols = \{([^}]*)\}""").find(source)?.groupValues?.get(1).orEmpty()
            .let { block -> Regex(""":\s*"([^"]+)"""").findAll(block).map { it.groupValues[1] } }
        val dashboard = (arrays + defaults).filter { it != "none" }.toSet()
        assertTrue(dashboard.isNotEmpty())
        assertEquals(dashboard, dashboardSymbols.toSet())
    }
}
