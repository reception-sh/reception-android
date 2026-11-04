package sh.reception

import java.net.URLDecoder

/**
 * A place in your app that a link card can open: a tab, a screen, a dialog, the paywall, anything your code can show.
 * List them in `Reception.screens`; the dashboard then offers them by name, and only people whose app version has a
 * screen get links to it.
 *
 * ```kotlin
 * Reception.screens = listOf(
 *     ReceptionScreen("settings") { navController.navigate("settings") },
 *     ReceptionScreen("order", value = "Order number") { number -> navController.navigate("order/$number") },
 * )
 * ```
 *
 * The SDK closes the chat it presented, then runs `open` on the main thread. An ID uses letters, digits, `_`, `-` or
 * `.`, at most 64 characters, and starts with a letter or digit; IDs that don't, and repeated IDs, are ignored.
 */
public class ReceptionScreen {
    /** The name the dashboard shows and links use, such as `settings`. */
    public val id: String

    /** What the value is called in the dashboard, such as "Order number"; null when the screen takes none. */
    public val valueName: String?
    internal val open: (String?) -> Unit

    /** A screen that opens the same way every time. */
    public constructor(id: String, open: () -> Unit) {
        this.id = id
        valueName = null
        this.open = { open() }
    }

    /**
     * A screen that needs a value from the link, such as an order number. [value] names it for the team, and every
     * link to this screen carries one.
     */
    public constructor(id: String, value: String, open: (String) -> Unit) {
        this.id = id
        valueName = value
        this.open = { link -> if (link != null) open(link) }
    }
}

/**
 * A link card that opens one of the host's screens: `reception-screen:<id>`, with `?value=<value>` for a screen that
 * takes one.
 */
internal data class ScreenLink(val id: String, val value: String?) {
    companion object {
        const val SCHEME = "reception-screen"

        /** At most this many screens are reported and opened. */
        const val LIMIT = 50
        private const val VALUE_NAME_LIMIT = 40
        private val validId = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

        fun parse(url: String): ScreenLink? {
            val colon = url.indexOf(':')
            if (colon < 0 || !url.substring(0, colon).equals(SCHEME, ignoreCase = true)) return null
            val rest = url.substring(colon + 1).substringBefore('#')
            val id = decoded(rest.substringBefore('?')) ?: return null
            if (!isValidId(id)) return null
            val query = rest.substringAfter('?', missingDelimiterValue = "")
            val item = query.split('&').firstOrNull { (decoded(it.substringBefore('=')) ?: "") == "value" }
            val value = item?.takeIf { '=' in it }?.let { decoded(it.substringAfter('=')) ?: return null }
            return ScreenLink(id, value?.takeIf { it.isNotEmpty() })
        }

        fun isValidId(id: String): Boolean = validId.matches(id)

        /** The screens the host listed that can be opened: valid IDs, each once (the first wins), at most [LIMIT]. */
        fun usable(screens: List<ReceptionScreen>): List<ReceptionScreen> {
            val seen = mutableSetOf<String>()
            return screens.filter { isValidId(it.id) && seen.add(it.id) }.take(LIMIT)
        }

        /** The screens as the service stores them for this device. */
        fun report(screens: List<ReceptionScreen>): List<Map<String, String>> = usable(screens).map { screen ->
            buildMap {
                put("id", screen.id)
                screen.valueName?.let { put("value", prefix(it, VALUE_NAME_LIMIT)) }
            }
        }

        /** Percent-decoding as URL components do it: `+` stays a plus sign; a malformed escape fails. */
        private fun decoded(part: String): String? = try {
            URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            null
        }

        /** At most [limit] characters, never ending inside a surrogate pair. */
        private fun prefix(text: String, limit: Int): String {
            if (text.codePointCount(0, text.length) <= limit) return text
            return text.substring(0, text.offsetByCodePoints(0, limit))
        }
    }
}
