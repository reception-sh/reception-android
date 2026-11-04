package sh.reception.client

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sh.reception.FacadeState
import sh.reception.ReceptionLocalization
import sh.reception.ReceptionState
import sh.reception.SDKVersion
import java.util.Locale
import java.util.TimeZone

internal object DeviceInfo {
    /** What this SDK can show; the server gates link cards and screen links on these, not on the version. */
    private val capabilities = listOf("linkCards", "screens")

    // The app's version cannot change while it runs.
    private var appVersion: Pair<String, String>? = null

    fun payload(
        context: Context,
        identity: DeviceIdentity,
        identityToken: String? = null,
        registration: Boolean = false,
    ): String {
        val (version, build) = appVersion(context)
        val body = buildJsonObject {
            put("platform", "android")
            put("sdkVersion", SDKVersion.current)
            put("appVersion", version)
            put("appBuild", build)
            put("osVersion", Build.VERSION.RELEASE.orEmpty().trim().ifEmpty { Build.VERSION.SDK_INT.toString() })
            put("deviceModel", "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { "Android" })
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezone", TimeZone.getDefault().id)
            put("language", ReceptionLocalization.appLanguage)
            put("bundleId", context.packageName)
            identity.externalUserId?.let { put("externalUserId", it) }
            identity.name?.let { put("name", it) }
            identity.email?.let { put("email", it) }
            if (!registration && identity.pushToken != null && identity.pushEnvironment != null) {
                put("pushToken", identity.pushToken)
                put("pushEnvironment", identity.pushEnvironment)
            }
            put("paywalls", JsonArray(FacadeState.paywalls))
            put("metadata", JsonObject(identity.metadata.mapValues { JsonPrimitive(it.value) }))
            put("updatesAppBadge", ReceptionState.updatesAppBadge)
            put("screens", JsonArray(FacadeState.screens.map { screen -> JsonObject(screen.mapValues { JsonPrimitive(it.value) }) }))
            put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
            FacadeState.subscribed?.let { put("subscribed", it) }
            FacadeState.appearanceReport?.let { put("appearance", it) }
            identityToken?.let { put("identityToken", it) }
        }
        return body.toString()
    }

    private fun appVersion(context: Context): Pair<String, String> {
        appVersion?.let { return it }
        val info = try {
            packageInfo(context)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val version = info?.versionName?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0"
        val build = info?.let { PackageInfoCompat.getLongVersionCode(it).toString() } ?: "1"
        return (version to build).also { appVersion = it }
    }

    private fun packageInfo(context: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
}
