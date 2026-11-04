package sh.reception.configuration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.reception.Reception

/** The report uses exactly the field names of the backend's `appAppearanceSchema` (`src/lib/app-appearance.ts`). */
class HostAppearanceReportTest {
    private val schema = mapOf(
        "" to setOf("usesRemoteAppearance", "colors", "text", "colorScheme", "showsCloseButton", "closeIcon", "closeImage",
            "fontDesign", "fontFamily", "fadesOlderMessages", "welcomeIcon", "visibility"),
        "colors" to setOf("accent", "onAccent", "background", "incoming", "incomingText"),
        "text" to setOf("title", "welcomeTitle", "welcomeText"),
        "welcomeIcon" to setOf("symbol", "image", "color", "size", "cornerRadius", "offset"),
        "visibility" to setOf("title", "welcomeIcon", "welcomeTitle", "welcomeText", "pushStatus"),
    )

    private fun everything() = Reception.Appearance().apply {
        accentColor = Color(0xFF635BFF)
        onAccentColor = Color.White
        chatBackground = Color.Black
        incomingBackground = Color(0xFF2C2C2E)
        incomingForeground = Color(0xFFEEEEEE)
        title = "Help"
        welcomeTitle = "Hi"
        welcomeText = "We read everything."
        preferredColorScheme = Reception.ColorScheme.DARK
        showsCloseButton = true
        closeIcon = Reception.CloseIcon.CHEVRON_DOWN
        closeImage = ColorPainter(Color.Red)
        fontDesign = Reception.FontDesign.ROUNDED
        fontFamily = FontFamily.Serif
        fadesOlderMessages = false
        welcomeIcon = Reception.WelcomeIcon.symbol("heart.fill")
        welcomeIconColor = Color.Red
        welcomeIconSize = 60.4.dp
        welcomeIconCornerRadius = 8.dp
        welcomeIconOffset = DpOffset(4.dp, (-6).dp)
        hidesTitle = true
        hidesWelcomeTitle = true
        hidesWelcomeText = true
        hidesPushStatus = true
    }

    @Test
    fun aFullAppearanceUsesOnlyTheSchemaFields() {
        val report = HostAppearanceReport.payload(everything(), usesRemoteAppearance = false) { null }
        assertEquals(schema.getValue(""), report.keys)
        for (group in schema.keys - "" - "welcomeIcon" - "visibility") {
            assertEquals(group, schema.getValue(group), report.getValue(group).jsonObject.keys)
        }
        assertEquals(schema.getValue("welcomeIcon") - "image", report.getValue("welcomeIcon").jsonObject.keys)
        assertEquals(schema.getValue("visibility") - "welcomeIcon", report.getValue("visibility").jsonObject.keys)
        assertEquals("""{"light":"#635BFF","dark":"#635BFF"}""", (report.getValue("colors") as JsonObject).getValue("accent").toString())
        assertEquals("""{"symbol":"heart.fill","color":{"light":"#FF0000","dark":"#FF0000"},"size":60,"cornerRadius":8,"offset":{"x":4,"y":-6}}""",
            report.getValue("welcomeIcon").toString())
        assertEquals("\"rounded\"", report.getValue("fontDesign").toString())
        assertEquals("\"custom\"", report.getValue("fontFamily").toString())
        assertEquals("\"chevron.down\"", report.getValue("closeIcon").toString())
        assertEquals("true", report.getValue("closeImage").toString())
    }

    @Test
    fun theDefaultAppearanceReportsOnlyTheSettingAndTheThemeAccent() {
        val report = HostAppearanceReport.payload(Reception.Appearance(), usesRemoteAppearance = true) {
            Color(0xFF6750A4) to Color(0xFFD0BCFF)
        }
        assertEquals("""{"usesRemoteAppearance":true,"colors":{"accent":{"light":"#6750A4","dark":"#D0BCFF"}}}""", report.toString())
        assertEquals("""{"usesRemoteAppearance":true}""", HostAppearanceReport.payload(Reception.Appearance(), true) { null }.toString())
    }

    @Test
    fun hiddenOrImageWelcomeIconsAreReportedWithoutContent() {
        val hidden = HostAppearanceReport.payload(Reception.Appearance().apply { welcomeIcon = Reception.WelcomeIcon.hidden }, true) { null }
        assertEquals("""{"welcomeIcon":false}""", hidden.getValue("visibility").toString())
        val image = Reception.Appearance().apply { welcomeIcon = Reception.WelcomeIcon.image(ColorPainter(Color.Blue)) }
        assertEquals("""{"image":true}""", HostAppearanceReport.payload(image, true) { null }.getValue("welcomeIcon").toString())
    }

    @Test
    fun theCheckedSchemaMatchesTheBackend() {
        val source = BackendContract.source("src/lib/app-appearance.ts") ?: return
        val body = source.substringAfter("export const appAppearanceSchema = z.object({").substringBefore("\n});")
        for (field in schema.values.flatten()) {
            assertTrue("$field is not in appAppearanceSchema", Regex("""\b$field\s*[:,]""").containsMatchIn(body))
        }
        val topLevel = Regex("""^ {2}(\w+):""", RegexOption.MULTILINE).findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(schema.getValue(""), topLevel)
    }
}
