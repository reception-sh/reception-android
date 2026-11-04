package sh.reception

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.reception.strings.Strings

/** Reads the generated tables; Robolectric supplies `android.icu` for the plural rules. */
@RunWith(RobolectricTestRunner::class)
class ReceptionStringsTest {
    @Test
    fun everyLanguageHasEveryKey() {
        val english = Strings.languages.getValue("en")
        assertEquals(ReceptionLocalization.supportedLanguages.toSet(), Strings.languages.keys)
        for ((language, table) in Strings.languages) {
            assertEquals(language, english.strings.keys, table.strings.keys)
            assertEquals(language, english.plurals.keys, table.plurals.keys)
        }
    }

    @Test
    fun readsTheResolvedLanguageWithAndroidOverlay() {
        assertEquals("Nachricht senden", ReceptionLocalization.string("de", Strings.sendMessage))
        assertEquals("Bei Google Play bewerten", ReceptionLocalization.string("de", Strings.rateOnGooglePlay))
        assertEquals("Support replies", ReceptionLocalization.string("en", Strings.supportReplies))
        assertEquals("Ответы поддержки", ReceptionLocalization.string("ru", Strings.supportReplies))
    }

    @Test
    fun formatsPlaceholdersInTheLanguageLocale() {
        assertEquals("Sending again in 5 s", ReceptionLocalization.string("en", Strings.sendingAgain, "in 5 s"))
    }

    @Test
    fun selectsRussianPluralCategories() {
        val key = Strings.Plurals.newMessages
        assertEquals("1 новое сообщение", ReceptionLocalization.plural("ru", key, 1))
        assertEquals("3 новых сообщения", ReceptionLocalization.plural("ru", key, 3))
        assertEquals("5 новых сообщений", ReceptionLocalization.plural("ru", key, 5))
        assertEquals("1 new message", ReceptionLocalization.plural("en", key, 1))
        assertEquals("2 new messages", ReceptionLocalization.plural("en", key, 2))
    }

    @Test
    fun languagesWithoutPluralFormsUseOther() {
        assertEquals("3件の新着メッセージ", ReceptionLocalization.plural("ja", Strings.Plurals.newMessages, 3))
    }
}
