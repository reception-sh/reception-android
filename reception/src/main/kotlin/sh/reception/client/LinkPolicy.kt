package sh.reception.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.core.net.toUri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Which link card URLs the chat opens, and which of them lead back into the host app. Android has no
 * `CFBundleURLTypes`: a URL is the host's own when one of its activities handles it ([handlers] lists the packages of
 * the activities that would open it). Intent filters match by path and compare schemes case-sensitively, so answers
 * are cached per complete URL (its scheme in lowercase), the most recent [cacheSize]. Main thread only.
 */
internal class LinkPolicy(private val packageName: String, private val handlers: (String) -> List<String>) {
    private val hostLinks = object : LinkedHashMap<String, Boolean>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > cacheSize
    }

    /** True when the host app itself opens [url], through a custom scheme or an app link. */
    fun isHostScheme(url: String): Boolean {
        if (scheme(url) == null) return false
        val normalized = normalizedScheme(url)
        return hostLinks.getOrPut(normalized) { packageName in handlers(normalized) }
    }

    companion object {
        private const val cacheSize = 64
        private val systemSchemes = setOf("https", "http", "mailto", "tel", "sms")
        private val blockedSchemes = setOf("javascript", "data", "file", "blob", "about", "vbscript")
        private val schemeName = Regex("^[a-z][a-z0-9+.-]*$")
        private val schemePrefix = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        private var shared: LinkPolicy? = null

        /** The host app's policy, created once. */
        fun shared(context: Context): LinkPolicy = shared ?: of(context).also { shared = it }

        /** A policy that asks [context]'s package manager. */
        fun of(context: Context): LinkPolicy = LinkPolicy(context.packageName) { url ->
            activities(context.packageManager, url).map { it.activityInfo.packageName }
        }

        /** The service applies the same rule when a link is saved; this check only guards against unexpected data. */
        fun isAllowed(url: String): Boolean {
            val scheme = scheme(url) ?: return false
            if (scheme in systemSchemes) return true
            return scheme !in blockedSchemes && schemeName.matches(scheme)
        }

        /** A web page the chat can show in a Custom Tab. Google Play links stay with the system so Play opens them. */
        fun isWebLink(url: String): Boolean {
            val scheme = scheme(url)
            if (scheme != "https" && scheme != "http") return false
            val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return false
            return host != "play.google.com"
        }

        /**
         * [url] with its scheme in lowercase, as `Uri.normalizeScheme` does: intent filters compare schemes
         * case-sensitively, while the allowlist above accepts any case.
         */
        fun normalizedScheme(url: String): String {
            val prefix = schemePrefix.find(url) ?: return url
            val scheme = prefix.groupValues[1]
            return scheme.lowercase(Locale.ROOT) + url.substring(scheme.length)
        }

        private fun scheme(url: String): String? = schemePrefix.find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

        // Only the host's own package matters, and an app always sees itself, so no <queries> entry is needed.
        @SuppressLint("QueryPermissionsNeeded")
        private fun activities(packageManager: PackageManager, url: String): List<ResolveInfo> {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }
    }
}
