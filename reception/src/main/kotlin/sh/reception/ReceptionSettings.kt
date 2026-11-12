package sh.reception

import android.content.Context
import android.content.pm.ApplicationInfo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import sh.reception.client.ReceptionAPI
import sh.reception.client.preferencesChanged

/** Facade settings sent with the next device update, and the test service switch. Main thread only. */
internal object ReceptionSettings {
    fun setLanguageOverride(value: String?) {
        if (ReceptionLocalization.languageOverride == value) return
        ReceptionLocalization.languageOverride = value
        if (value != null) {
            Log.debug { "Language override set to ${ReceptionLocalization.canonical(value)}" }
        } else {
            Log.debug { "Language override cleared" }
        }
        ReceptionState.session?.preferencesChanged()
    }

    fun setUpdatesAppBadge(value: Boolean) {
        if (ReceptionState.updatesAppBadge == value) return
        ReceptionState.updatesAppBadge = value
        ReceptionState.session?.preferencesChanged()
    }

    /** `Reception.screens`; the device update reports the usable ones. */
    var screens: List<ReceptionScreen> = emptyList()
        private set

    /** `Reception.opensWebLinksInApp`. */
    var opensWebLinksInApp: Boolean = true

    fun setScreens(value: List<ReceptionScreen>) {
        screens = value
        FacadeState.screens = ScreenLink.report(value)
        ReceptionState.session?.preferencesChanged()
    }

    /** Release hosts cannot redirect the SDK: only a debuggable app may use another service. */
    fun configureForTesting(context: Context, appId: String, serviceUrl: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return Log.error { "configureForTesting refused, the app is not debuggable" }
        }
        val url = serviceUrl.toHttpUrlOrNull() ?: return Log.error { "configureForTesting refused a malformed URL" }
        ReceptionAPI.serviceUrl = url
        ReceptionState.configure(context, appId)
    }
}
