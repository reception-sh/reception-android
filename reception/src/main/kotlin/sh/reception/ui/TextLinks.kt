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
import sh.reception.Log
import sh.reception.client.LinkPolicy

/**
 * Opens a web address tapped in message text, with the chat left open: an installed app that claims the link (as
 * Messages and Mail do), else a Custom Tab over the chat in the accent color, else the system. Screens and links into
 * the host app arrive with the link cards.
 */
internal object TextLinks {
    fun open(context: Context, url: String, toolbarColor: Int) {
        if (!LinkPolicy.isAllowed(url)) return
        if (LinkPolicy.isWebLink(url) && (openInClaimingApp(context, url) || openCustomTab(context, url, toolbarColor))) return
        openExternally(context, url)
    }

    // resolveActivity(Intent, Int) is only reached below Android 11, where it is the current API.
    @Suppress("DEPRECATION")
    private fun openInClaimingApp(context: Context, url: String): Boolean {
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

    private fun openCustomTab(context: Context, url: String, toolbarColor: Int): Boolean = try {
        CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
            .build().launchUrl(context, url.toUri())
        Log.debug { "Web page presented" }
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun openExternally(context: Context, url: String) {
        if (!start(context, Intent(Intent.ACTION_VIEW, url.toUri()))) Log.info { "No app opens this link" }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
