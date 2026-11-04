package sh.reception.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Which link card URLs the chat opens, and which of them lead back into the host app. Android has no
 * `CFBundleURLTypes`: a URL is the host's own when one of its activities handles it ([handlers] lists the packages of
 * the activities that would open it). Answers are cached per scheme and host. Main thread only.
 */
internal class LinkPolicy(private val packageName: String, private val handlers: (String) -> List<String>) {
    private val hostLinks = mutableMapOf<String, Boolean>()

    /** True when the host app itself opens [url], through a custom scheme or an app link. */
    fun isHostScheme(url: String): Boolean {
        val scheme = scheme(url) ?: return false
        val key = "$scheme://${host(url).orEmpty()}"
        return hostLinks.getOrPut(key) { packageName in handlers(url) }
    }

    companion object {
        private val systemSchemes = setOf("https", "http", "mailto", "tel", "sms")
        private val blockedSchemes = setOf("javascript", "data", "file", "blob", "about", "vbscript")
        private val schemeName = Regex("^[a-z][a-z0-9+.-]*$")
        private val schemePrefix = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        private var shared: LinkPolicy? = null

        /** The host app's policy, created once. */
        fun shared(context: Context): LinkPolicy = shared ?: LinkPolicy(context.packageName) { url ->
            activities(context.packageManager, url).map { it.activityInfo.packageName }
        }.also { shared = it }

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

        private fun scheme(url: String): String? = schemePrefix.find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

        private fun host(url: String): String? =
            url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }

        private fun activities(packageManager: PackageManager, url: String): List<ResolveInfo> {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }
    }
}
