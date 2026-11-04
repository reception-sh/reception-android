package sh.reception.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import sh.reception.Log
import sh.reception.ReceptionLocalization
import sh.reception.client.ApplicationLifecycle

/**
 * Hands URLs to Android: an installed app that claims a web link, a Custom Tab, or the system. Each answers whether an
 * activity accepted the URL. They start from the resumed activity (the chat sheet, or the host after it closed), else
 * from the application in a new task.
 */
internal object LinkOpener {
    private const val PLAY_STORE = "com.android.vending"

    val context: Context?
        get() = ApplicationLifecycle.currentActivity ?: ReceptionLocalization.context

    /** An installed app other than a browser that claims [url], as Messages and Mail hand links to apps on iOS. */
    // resolveActivity(Intent, Int) is only reached below Android 11, where it is the current API.
    @Suppress("DEPRECATION")
    fun openInClaimingApp(url: String): Boolean {
        val context = context ?: return false
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return start(context, intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER))
        }
        // Below Android 11 only a default handler that is not the browser counts as claiming the link.
        val manager = context.packageManager
        val handler = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val browser = manager.resolveActivity(Intent(Intent.ACTION_VIEW, "https://".toUri()),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (handler == null || handler == "android" || handler == browser) return false
        return start(context, intent)
    }

    /** A Custom Tab over the chat, its toolbar in [toolbarColor]. */
    fun openCustomTab(url: String, toolbarColor: Int): Boolean {
        val context = context ?: return false
        return try {
            val intent = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
                .build()
            if (context !is Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.launchUrl(context, url.toUri())
            Log.debug { "Web page presented" }
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** The system's handling; a Google Play listing opens in the Play Store app when it is installed. */
    fun openExternally(url: String): Boolean {
        val context = context ?: return false
        val listing = playStoreListing(url)
        if (listing != null && start(context, Intent(Intent.ACTION_VIEW, listing.toUri()).setPackage(PLAY_STORE))) return true
        val opened = start(context, Intent(Intent.ACTION_VIEW, url.toUri()))
        if (!opened) Log.info { "No app opens this link" }
        return opened
    }

    /** `market://details?id=…` for a `https://play.google.com/store/apps/details?id=…` link. */
    internal fun playStoreListing(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host != "play.google.com" || parsed.encodedPath != "/store/apps/details") return null
        val id = parsed.queryParameter("id")?.takeIf { it.isNotEmpty() } ?: return null
        return "market://details?id=$id"
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
