package sh.reception.ui

import android.util.Patterns
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import java.util.Locale

/**
 * Message text with its web addresses tappable, in the bubble's own color and underlined, like Messages. Only
 * `http` and `https` are linked (a bare domain counts as `http`); other schemes and e-mail addresses stay plain text.
 */
internal object LinkedText {
    fun annotated(text: String, linkColor: Color, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
        val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        var position = 0
        for (match in links(text)) {
            append(text.substring(position, match.first))
            val url = match.second
            withLink(LinkAnnotation.Url(url, styles) { open(url) }) {
                append(text.substring(match.first, match.third))
            }
            position = match.third
        }
        append(text.substring(position))
    }

    /** Start, URL and end of every web address in [text]. */
    fun links(text: String): List<Triple<Int, String, Int>> {
        val matcher = Patterns.WEB_URL.matcher(text)
        val result = mutableListOf<Triple<Int, String, Int>>()
        while (matcher.find()) {
            val start = matcher.start()
            if (start > 0 && text[start - 1] == '@') continue
            val found = matcher.group()
            val scheme = found.substringBefore("://", missingDelimiterValue = "").lowercase(Locale.ROOT)
            val url = when (scheme) {
                "http", "https" -> found
                "" -> if (found.contains(':')) continue else "http://$found"
                else -> continue
            }
            result += Triple(start, url, matcher.end())
        }
        return result
    }
}
