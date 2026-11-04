package sh.reception

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import sh.reception.client.ReceptionAPI
import sh.reception.client.preferencesChanged

/** In-app support chat. Call every member from the main thread; a call from another thread is posted there. */
public object Reception {
    /** Minimum severity of developer logs, from quietest to most detailed. */
    public enum class LogLevel { OFF, ERROR, INFO, DEBUG }

    private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Version of the SDK, for example "1.0.0". Sent with device registration and updates. */
    @JvmStatic
    public val version: String
        get() = SDKVersion.current

    /** Minimum level that is emitted. Default [LogLevel.INFO]. Set before `configure`. */
    @JvmStatic
    public var logLevel: LogLevel
        get() = Log.level
        set(value) {
            Log.level = value
        }

    /** Receives every emitted line instead of Logcat. `null` (default) logs with the tag "Reception". */
    @JvmStatic
    public var logHandler: ((LogLevel, String) -> Unit)?
        get() = Log.handler
        set(value) {
            Log.handler = value
        }

    /** True once a valid App ID has been accepted for this process. */
    @JvmStatic
    public val isConfigured: Boolean
        get() = ReceptionState.session != null

    /** Unread replies from the team. Show a badge only above zero. */
    @JvmStatic
    public val unreadCount: StateFlow<Int>
        get() = ReceptionState.unreadCount

    /** Whether the chat is visible, not whether it was requested. */
    @JvmStatic
    public val isChatOpen: StateFlow<Boolean>
        get() = ReceptionState.isChatOpen

    /** Receives chat events on the main thread. Keep it light; assignment replaces the previous handler. */
    @JvmStatic
    public var onEvent: ((ReceptionEvent) -> Unit)?
        get() = ReceptionState.onEvent
        set(value) = MainThread.run { ReceptionState.onEvent = value }

    /** Null follows the app's language. Set a BCP 47 tag for an in-app language picker. */
    @JvmStatic
    public var languageOverride: String?
        get() = ReceptionLocalization.languageOverride
        set(value) = MainThread.run {
            if (ReceptionLocalization.languageOverride == value) return@run
            ReceptionLocalization.languageOverride = value
            if (value != null) {
                Log.debug { "Language override set to ${ReceptionLocalization.canonical(value)}" }
            } else {
                Log.debug { "Language override cleared" }
            }
            ReceptionState.session?.preferencesChanged()
        }

    /**
     * Shows the unread count on the SDK's notifications and removes them once read. Set to `false` if the app owns
     * its badge; the service then omits the badge from pushes.
     */
    @JvmStatic
    public var updatesAppBadge: Boolean
        get() = ReceptionState.updatesAppBadge
        set(value) = MainThread.run {
            if (ReceptionState.updatesAppBadge == value) return@run
            ReceptionState.updatesAppBadge = value
            ReceptionState.session?.preferencesChanged()
        }

    /** Connects your app to Reception with the App ID from your dashboard. Keeps only the application context. */
    @JvmStatic
    public fun configure(context: Context, appId: String): Unit = MainThread.run {
        ReceptionState.configure(context.applicationContext, appId)
    }

    /**
     * Connects to another Reception service, for example a local backend. Only for Reception's own test apps; call it
     * once instead of [configure]. Accepted only when the app is debuggable.
     */
    @ReceptionInternalApi
    @JvmStatic
    public fun configureForTesting(context: Context, appId: String, serviceUrl: String): Unit = MainThread.run {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return@run Log.error { "configureForTesting refused, the app is not debuggable" }
        }
        val url = serviceUrl.toHttpUrlOrNull() ?: return@run Log.error { "configureForTesting refused a malformed URL" }
        ReceptionAPI.serviceUrl = url
        ReceptionState.configure(context.applicationContext, appId)
    }

    /**
     * Labels the support device with your user's details. The service shows them as unverified.
     * Call [logout] when users switch; this method never resets the chat.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String?, name: String? = null, email: String? = null): Unit = MainThread.run {
        ReceptionIdentity.identify(userId, name, email)
    }

    /**
     * Verifies the signed-in user with a token your server signs with the identity secret (HS256 JWT with `user_id`
     * and `exp`). Call it after [configure] on every launch while a user is signed in, and after sign-in. The token
     * stays in memory. Rejections arrive as [ReceptionEvent.IdentityRejected].
     */
    @JvmStatic
    @JvmName("identifyWithToken")
    public fun identify(token: String): Unit = MainThread.run { ReceptionIdentity.identify(token) }

    /** Replaces the metadata sent with the next device update: up to 20 entries, keys 1–64, values up to 512. */
    @JvmStatic
    public fun setMetadata(metadata: Map<String, String>): Unit = MainThread.run {
        ReceptionIdentity.setMetadata(metadata.toMap())
    }

    /** The FCM registration token, from `FirebaseMessaging.getToken()` or `onNewToken`. */
    @JvmStatic
    public fun setPushToken(token: String): Unit = MainThread.run { ReceptionAccount.setPushToken(token) }

    /** Dismisses chat, resets locally immediately, and revokes the old session in the background. */
    @JvmStatic
    public fun logout(): Unit = MainThread.run { ReceptionAccount.logout() }

    /**
     * Deletes this device's support data, then resets the local session. Call it in your account deletion flow
     * instead of [logout]. Throws [ReceptionError] and keeps local data for a retry when the service could not delete.
     */
    @JvmStatic
    public suspend fun deleteData(): Unit = withContext(Dispatchers.Main.immediate) { ReceptionAccount.deleteData() }

    /** [deleteData] for callers without coroutines: [callback] receives null on success, on the main thread. */
    @JvmStatic
    public fun deleteData(callback: (ReceptionError?) -> Unit) {
        callbacks.launch {
            val error = try {
                ReceptionAccount.deleteData()
                null
            } catch (error: ReceptionError) {
                error
            }
            callback(error)
        }
    }
}

/** Facade values the device payload reports; the paywall, screen, paid access and appearance parts fill them. */
internal object FacadeState {
    /** `availablePaywalls` as registration entries. */
    var paywalls: List<JsonObject> = emptyList()

    /** `screens` as the service stores them: `{ id, value? }`. */
    var screens: List<Map<String, String>> = emptyList()

    /** Paid access from Play Billing; null while unknown. */
    var subscribed: Boolean? = null

    /** The host appearance report; null until the appearance model exists. */
    var appearanceReport: JsonObject? = null
}
