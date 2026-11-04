package sh.reception

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceptionLocalizationTest {
    private fun resolve(tag: String) = ReceptionLocalization.resolveLanguage(tag)

    @Test
    fun bundlesTheFortyEightIOSLanguages() {
        assertEquals(48, ReceptionLocalization.supportedLanguages.toSet().size)
    }

    @Test
    fun matchesRegionalAndScriptRules() {
        assertEquals("fr-CA", resolve("fr-CA"))
        assertEquals("fr", resolve("fr-BE"))
        assertEquals("pt-PT", resolve("pt-PT"))
        assertEquals("pt-BR", resolve("pt"))
        assertEquals("pt-BR", resolve("pt-AO"))
        assertEquals("es", resolve("es"))
        assertEquals("es", resolve("es-ES"))
        assertEquals("es-419", resolve("es-MX"))
        assertEquals("es-419", resolve("es-419"))
        assertEquals("zh-Hans", resolve("zh"))
        assertEquals("zh-Hans", resolve("zh-CN"))
        assertEquals("zh-Hans", resolve("zh-Hans-HK"))
        assertEquals("zh-Hant", resolve("zh-TW"))
        assertEquals("zh-Hant", resolve("zh-Hant"))
        assertEquals("zh-HK", resolve("zh-Hant-HK"))
        assertEquals("zh-HK", resolve("zh-MO"))
        assertEquals("nb", resolve("no"))
        assertEquals("nb", resolve("nn-NO"))
        assertEquals("nb", resolve("nb-NO"))
        assertEquals("de", resolve("de-AT"))
        assertEquals("en", resolve("sw"))
    }

    @Test
    fun canonicalizesLegacyAndMalformedTags() {
        assertEquals("he", ReceptionLocalization.canonical("iw"))
        assertEquals("id", ReceptionLocalization.canonical("in_ID").substringBefore('-'))
        assertEquals("ro", ReceptionLocalization.canonical("mo"))
        assertEquals("pt-BR", ReceptionLocalization.canonical(" pt_br "))
        assertEquals("de-DE", ReceptionLocalization.canonical("de-DE-u-ca-gregory"))
        assertEquals("en", ReceptionLocalization.canonical("not a tag"))
        assertEquals("en", ReceptionLocalization.canonical("de-1996-1996"))
        assertEquals("en", ReceptionLocalization.canonical("de-u-ca-gregory-u-nu-latn"))
        assertEquals("he", resolve("iw-IL"))
    }
}
