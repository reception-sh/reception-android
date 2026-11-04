package sh.reception.configuration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.reception.Reception
import sh.reception.ReceptionColor
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionLocalization

/** Decoding of the published envelope and the resolution rules of `REMOTE_APPEARANCE.md`. */
class RemoteAppearanceTest {
    private val uuid = "0F8FAD5B-D9CB-469F-A165-70867728950E"

    // Built-in texts are read in a fixed language, so no Android locale is needed.
    @Before
    fun setUp() {
        ReceptionLocalization.languageOverride = "en"
    }

    @After
    fun tearDown() {
        ReceptionLocalization.languageOverride = null
    }

    private fun remote(appearance: String): RemoteAppearance {
        val envelope = AppearanceCache.decode("""{"schemaVersion":1,"revision":3,"appearance":$appearance}""")
        return requireNotNull(envelope).appearance
    }

    private fun resolve(appearance: String, local: Reception.Appearance = Reception.Appearance(), languages: List<String> = listOf("en", "en")) =
        remote(appearance).applying(local, imageUrl = { ReceptionAppearance.imagePath(it)?.let { path -> "https://service/v1/$path" } },
            languages = languages)

    @Test
    fun theDocumentedExampleDecodesAndUnknownFieldsAreIgnored() {
        val envelope = AppearanceCache.decode("""
            {"schemaVersion":1,"revision":3,"future":{"x":1},"appearance":{
              "colors":{"accent":{"light":"#635BFF","dark":"#A59FFF"},"onAccent":{"light":"#FFFFFF","dark":"#151225"}},
              "text":{"title":{"en":"Customer care","de":"Kundenservice"}},"colorScheme":"system","showsCloseButton":true,
              "closeIcon":"chevron.down","fontDesign":"rounded","fadesOlderMessages":true,"newField":"ignored"},
             "pushPrompt":"replies"}
        """.trimIndent())
        assertNotNull(envelope)
        assertEquals(3L, envelope?.revision)
        assertEquals(PushPrompt.REPLIES, PushPrompt.from(envelope?.pushPrompt))
        val resolved = requireNotNull(envelope).appearance.applying(Reception.Appearance(), { null }, listOf("de", "de"))
        assertEquals("Kundenservice", resolved.title)
        assertEquals(Color(0xFF635BFF), resolved.accent.light)
        assertEquals(Color(0xFFA59FFF), resolved.accent.dark)
        assertEquals(Reception.CloseIcon.CHEVRON_DOWN, resolved.closeIcon)
        assertEquals(Reception.FontDesign.ROUNDED, resolved.fontDesign)
    }

    @Test
    fun aWrongJsonTypeRejectsTheEnvelopeAndIncompatibleSchemasAreRefused() {
        assertNull(AppearanceCache.decode("""{"schemaVersion":1,"revision":1,"appearance":{"showsCloseButton":"yes"}}"""))
        assertNull(AppearanceCache.decode("""{"schemaVersion":1,"revision":1,"appearance":{"visibility":{"title":null}}}"""))
        assertNull(AppearanceCache.decode("""{"schemaVersion":2,"revision":1,"appearance":{}}"""))
        assertNull(AppearanceCache.decode("""{"schemaVersion":1,"revision":-1,"appearance":{}}"""))
        assertNotNull(AppearanceCache.decode("""{"schemaVersion":2,"revision":1,"appearance":{}}""", requireCompatible = false))
        val oversized = """{"schemaVersion":1,"revision":1,"appearance":{"closeIcon":"${"x".repeat(70_000)}"}}"""
        assertNull(AppearanceCache.decode(oversized))
    }

    @Test
    fun visibilityOverridesTheLegacyHiddenListAndOmissionInherits() {
        val local = Reception.Appearance().apply { hidesWelcomeText = true }
        val resolved = resolve("""{"hidden":["title","welcomeTitle"],"visibility":{"title":true}}""", local)
        assertFalse(resolved.hidesTitle)
        assertTrue(resolved.hidesWelcomeTitle)
        assertTrue(resolved.hidesWelcomeText)
        assertFalse(resolve("""{"visibility":{"welcomeText":true}}""", local).hidesWelcomeText)
        assertEquals(AppearanceIcon.Hidden, resolve("""{"cards":{"offer":{"icon":"none"}}}""").offerCard.icon)
        assertNull(resolve("""{"cards":{"offer":{"icon":"none"}},"visibility":{"offerIcon":true}}""").offerCard.icon)
        assertEquals(AppearanceIcon.Hidden, resolve("""{"visibility":{"linkAccessory":false}}""").linkCard.accessory)
    }

    @Test
    fun explicitShowRestoresTheBuiltInWelcomeIconOverALocallyHiddenOne() {
        val hidden = Reception.Appearance().apply { welcomeIcon = Reception.WelcomeIcon.hidden }
        assertEquals(AppearanceIcon.Hidden, resolve("{}", hidden).welcomeIcon)
        assertNull(resolve("""{"visibility":{"welcomeIcon":true}}""", hidden).welcomeIcon)
        val heart = Reception.Appearance().apply { welcomeIcon = Reception.WelcomeIcon.symbol("heart.fill") }
        assertEquals(AppearanceIcon.Symbol("heart.fill"), resolve("""{"visibility":{"welcomeIcon":true}}""", heart).welcomeIcon)
        assertEquals(AppearanceIcon.Symbol("sparkles"), resolve("""{"welcomeIcon":{"symbol":"sparkles"}}""", hidden).welcomeIcon)
        assertEquals(AppearanceIcon.Hidden, resolve("""{"welcomeIcon":{"symbol":"sparkles"},"visibility":{"welcomeIcon":false}}""").welcomeIcon)
        assertEquals(AppearanceIcon.Remote("https://service/v1/appearance/images/${uuid.lowercase()}"),
            resolve("""{"welcomeIcon":{"symbol":"sparkles","image":"$uuid"}}""").welcomeIcon)
        // A symbol the SDK cannot draw keeps the local icon.
        assertEquals(AppearanceIcon.Symbol("heart.fill"), resolve("""{"welcomeIcon":{"symbol":"not.a.symbol"}}""", heart).welcomeIcon)
    }

    @Test
    fun aRemoteCloseSymbolReplacesALocalImageAndARemoteImageTakesPrecedence() {
        val painter = ColorPainter(Color.Red)
        val local = Reception.Appearance().apply { closeImage = painter }
        val symbol = resolve("""{"closeIcon":"arrow.down"}""", local)
        assertEquals(Reception.CloseIcon.ARROW_DOWN, symbol.closeIcon)
        assertNull(symbol.closeImage)
        val unknown = resolve("""{"closeIcon":"bell"}""", local)
        assertEquals(Reception.CloseIcon.XMARK, unknown.closeIcon)
        assertSame(painter, (unknown.closeImage as AppearanceIcon.Local).painter)
        val resource = Reception.Appearance().apply { closeImageResource = 7 }
        assertEquals(AppearanceIcon.LocalResource(7), resolve("{}", resource).closeImage)
        // One close image: setting the painter or the resource replaces the other.
        resource.closeImage = painter
        assertNull(resource.closeImageResource)
        resource.closeImageResource = 8
        assertNull(resource.closeImage)
        assertEquals(8, resource.closeImageResource)
        assertNotNull(resolve("""{"closeImage":"$uuid","closeIcon":"xmark"}""", local).remoteCloseImage)
        assertNull(resolve("""{"closeImage":"../../v1/devices"}""", local).remoteCloseImage)
        assertNull(resolve("""{"closeImage":"1-1-1-1-1"}""", local).remoteCloseImage)
    }

    @Test
    fun aZeroOffsetCentersAndOmissionKeepsTheLocalOffset() {
        val local = Reception.Appearance().apply { welcomeIconOffset = DpOffset(10.dp, 20.dp) }
        assertEquals(DpOffset(0.dp, 0.dp), resolve("""{"welcomeIcon":{"offset":{"x":0,"y":0}}}""", local).welcomeIconOffset)
        assertEquals(DpOffset(10.dp, 20.dp), resolve("{}", local).welcomeIconOffset)
        assertEquals(DpOffset(120.dp, (-120).dp), resolve("""{"welcomeIcon":{"offset":{"x":500,"y":-500}}}""").welcomeIconOffset)
        assertEquals(0.dp, resolve("""{"welcomeIcon":{"cornerRadius":0}}""", Reception.Appearance().apply { welcomeIconCornerRadius = 12.dp }).welcomeIconCornerRadius)
    }

    @Test
    fun translationsMatchTheAppLanguageThenItsSupportedMatchOnly() {
        val local = Reception.Appearance().apply { title = "Hilfe" }
        assertEquals("Hilfe", resolve("""{"text":{"title":{"en":"Help"}}}""", local, listOf("de", "de")).title)
        assertEquals("A", resolve("""{"text":{"title":{"ES-419":"A","es":"B"}}}""", local, listOf("es-MX", "es-419")).title)
        assertEquals("C", resolve("""{"text":{"title":{"pt-BR":"C","pt":"D"}}}""", local, listOf("pt-BR", "pt-BR")).title)
        assertEquals("Hilfe", resolve("""{"text":{"title":{"de":"   "}}}""", local, listOf("de", "de")).title)
        assertEquals("Hilfe", resolve("""{"text":{"title":{"de":"${"x".repeat(81)}"}}}""", local, listOf("de", "de")).title)
    }

    @Test
    fun colorVariantsInheritSeparatelyAndAFontDesignReplacesTheFamily() {
        val local = Reception.Appearance().apply {
            accentColor = ReceptionColor(Color.Green)
            fontFamily = FontFamily.Cursive
        }
        val resolved = resolve("""{"colors":{"accent":{"light":"#112233","dark":"red"}},"fontDesign":"serif"}""", local)
        assertEquals(Color(0xFF112233), resolved.accent.light)
        assertEquals(Color.Green, resolved.accent.dark)
        assertEquals(Reception.FontDesign.SERIF, resolved.fontDesign)
        assertNull(resolved.fontFamily)
        assertEquals(FontFamily.Cursive, resolve("""{"fontDesign":"comic"}""", local).fontFamily)
        assertNull(resolve("""{"colors":{"background":{"light":"#FFF"}}}""").background.light)
        assertNull(resolve("""{"colorScheme":"system"}""", Reception.Appearance().apply { preferredColorScheme = Reception.ColorScheme.DARK }).preferredColorScheme)
        // The app's appearance itself never changes.
        assertEquals(Color.Green, local.accentColor?.adaptive(null)?.light)
    }
}
