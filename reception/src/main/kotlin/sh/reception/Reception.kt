package sh.reception

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import sh.reception.client.ReceptionAPI
import sh.reception.client.preferencesChanged
import sh.reception.configuration.AppearanceIcon
import sh.reception.strings.Strings

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

    /**
     * The chat's look. The default follows the app: its accent color, the system colors and font, and the built-in
     * localized texts. Changes, in place or by assigning a new value, apply to an open chat at once. Dashboard settings
     * published as remote appearance override single properties without changing this value.
     */
    @JvmStatic
    public var appearance: Appearance
        get() = ReceptionAppearance.appearance
        set(value) = MainThread.run { ReceptionAppearance.appearance = value }

    /** Disable to use only this app's [appearance]: the SDK then neither fetches nor applies dashboard settings. */
    @JvmStatic
    public var usesRemoteAppearance: Boolean
        get() = ReceptionAppearance.remoteEnabled
        set(value) = MainThread.run { ReceptionAppearance.remoteEnabled = value }

    /** The chat's colors, texts, icons and switches. Read by the chat while it draws; main thread only. */
    public class Appearance {
        /**
         * Defaults to null: the app's primary color, from the enclosing `MaterialTheme` when the chat is embedded,
         * otherwise from the app theme's `colorPrimary` (or `colorAccent`).
         */
        public var accentColor: Color? by mutableStateOf(null)

        /** Defaults to null, using white text and symbols on accent-colored surfaces. */
        public var onAccentColor: Color? by mutableStateOf(null)

        /** Defaults to the localized "Support" title. */
        public var title: String
            get() = titleOverride ?: ReceptionLocalization.string(Strings.support)
            set(value) {
                titleOverride = value
            }

        /** Defaults to the localized "Questions, feedback, or a bug?" headline. */
        public var welcomeTitle: String
            get() = welcomeTitleOverride ?: ReceptionLocalization.string(Strings.questionsFeedbackOrABug)
            set(value) {
                welcomeTitleOverride = value
            }

        /** Defaults to the localized "You're chatting directly with our team." text. */
        public var welcomeText: String
            get() = welcomeTextOverride ?: ReceptionLocalization.string(Strings.youreChattingDirectlyWithOurTeam)
            set(value) {
                welcomeTextOverride = value
            }

        /** Defaults to false; the sheet shows its drag handle. */
        public var showsCloseButton: Boolean by mutableStateOf(false)

        /** Defaults to null, using the system background. */
        public var chatBackground: Color? by mutableStateOf(null)

        /** Defaults to null, using the secondary system background. */
        public var incomingBackground: Color? by mutableStateOf(null)

        /** Defaults to null, using the primary text color. */
        public var incomingForeground: Color? by mutableStateOf(null)

        /** Defaults to null, following the system's dark theme setting. */
        public var preferredColorScheme: ColorScheme? by mutableStateOf(null)

        /** Defaults to [CloseIcon.XMARK]. */
        public var closeIcon: CloseIcon by mutableStateOf(CloseIcon.XMARK)

        /** Defaults to null, using [closeIcon]. An image such as `painterResource(R.drawable.close)`, in its own colors. */
        public var closeImage: Painter? by mutableStateOf(null)

        /** Defaults to null, using the default system font. [FontDesign.ROUNDED] draws the default font on Android. */
        public var fontDesign: FontDesign? by mutableStateOf(null)

        /**
         * Defaults to null (system font). A family built from the app's bundled fonts; it takes precedence over a local
         * [fontDesign]. An explicit dashboard font design uses that system font instead.
         */
        public var fontFamily: FontFamily? by mutableStateOf(null)

        /** Defaults to true, fading older outgoing messages. */
        public var fadesOlderMessages: Boolean by mutableStateOf(true)

        /** Defaults to null, using the built-in chat bubbles symbol. */
        public var welcomeIcon: WelcomeIcon? by mutableStateOf(null)

        /** Defaults to null, using the tertiary text color. Applies to symbols, not images. */
        public var welcomeIconColor: Color? by mutableStateOf(null)

        /** Before font scaling. Defaults to null, using the built-in 44 dp. */
        public var welcomeIconSize: Dp? by mutableStateOf(null)

        /** Welcome image corner radius before font scaling (0–80). Defaults to 0. Applies to images, not symbols. */
        public var welcomeIconCornerRadius: Dp by mutableStateOf(0.dp)

        /** Defaults to zero. Moves the icon from its centered position without moving the welcome text. */
        public var welcomeIconOffset: DpOffset by mutableStateOf(DpOffset(0.dp, 0.dp))

        /** Defaults to false. */
        public var hidesTitle: Boolean by mutableStateOf(false)

        /** Defaults to false. */
        public var hidesWelcomeTitle: Boolean by mutableStateOf(false)

        /** Defaults to false. */
        public var hidesWelcomeText: Boolean by mutableStateOf(false)

        /**
         * Defaults to false. Hides the "You'll get a notification when we reply." line under the person's latest
         * message; the notification card is unaffected.
         */
        public var hidesPushStatus: Boolean by mutableStateOf(false)

        // Only text the app set itself is reported to the dashboard and kept over the built-in localized copy.
        internal var titleOverride: String? by mutableStateOf(null)
            private set
        internal var welcomeTitleOverride: String? by mutableStateOf(null)
            private set
        internal var welcomeTextOverride: String? by mutableStateOf(null)
            private set
    }

    /** The symbol of the optional close button. */
    public enum class CloseIcon(internal val symbol: String) { XMARK("xmark"), CHEVRON_DOWN("chevron.down"), ARROW_DOWN("arrow.down") }

    /** A system font design; Android has no rounded system face, so [ROUNDED] draws the default one. */
    public enum class FontDesign(internal val raw: String) {
        DEFAULT("default"), ROUNDED("rounded"), SERIF("serif"), MONOSPACED("monospaced")
    }

    /** A fixed light or dark chat. */
    public enum class ColorScheme { LIGHT, DARK }

    /** The icon above the welcome heading. */
    public class WelcomeIcon internal constructor(internal val icon: AppearanceIcon) {
        public companion object {
            /**
             * A symbol by its SF Symbols name, drawn in `welcomeIconColor`. The SDK draws the names the dashboard
             * offers; any other name shows the built-in icon.
             */
            @JvmStatic
            public fun symbol(name: String): WelcomeIcon = WelcomeIcon(AppearanceIcon.Symbol(name))

            /** An image such as `painterResource(R.drawable.logo)`, drawn in its own colors. */
            @JvmStatic
            public fun image(painter: Painter): WelcomeIcon = WelcomeIcon(AppearanceIcon.Local(painter))

            /** No icon. */
            @JvmStatic
            public val hidden: WelcomeIcon = WelcomeIcon(AppearanceIcon.Hidden)
        }
    }

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
    public fun identifyWithToken(token: String): Unit = MainThread.run { ReceptionIdentity.identify(token) }

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
