package sh.reception.configuration

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import sh.reception.Reception

/**
 * The appearance the chat draws: native defaults, then the app's `Reception.appearance`, then the activated remote
 * fields. Colors stay per mode, since the dashboard publishes light and dark variants separately.
 */
internal class ResolvedAppearance(
    /** Null variants use the app's primary color. */
    val accent: AdaptiveColor,
    /** Null variants use white. */
    val onAccent: AdaptiveColor,
    val background: AdaptiveColor,
    val incoming: AdaptiveColor,
    val incomingText: AdaptiveColor,
    val title: String,
    val welcomeTitle: String,
    val welcomeText: String,
    val showsCloseButton: Boolean,
    val preferredColorScheme: Reception.ColorScheme?,
    val closeIcon: Reception.CloseIcon,
    /** The app's close image, a painter or a drawable resource. */
    val closeImage: AppearanceIcon?,
    /** An uploaded close image; it takes precedence over [closeImage] and [closeIcon]. */
    val remoteCloseImage: String?,
    val fontDesign: Reception.FontDesign?,
    val fontFamily: FontFamily?,
    val fadesOlderMessages: Boolean,
    /** Null draws the built-in chat bubbles symbol. */
    val welcomeIcon: AppearanceIcon?,
    /** Null variants use the tertiary text color. */
    val welcomeIconColor: AdaptiveColor,
    val welcomeIconSize: Dp?,
    val welcomeIconCornerRadius: Dp,
    val welcomeIconOffset: DpOffset,
    val hidesTitle: Boolean,
    val hidesWelcomeTitle: Boolean,
    val hidesWelcomeText: Boolean,
    val hidesPushStatus: Boolean,
    val showsTeamPhotos: Boolean = false,
    val showsTeamNames: Boolean = false,
    val reviewCard: CardStyle = CardStyle(),
    val offerCard: CardStyle = CardStyle(),
    val linkCard: CardStyle = CardStyle(),
) {
    /** A card's remote style; null fields keep the built-in icon, accessory, colors and size. */
    class CardStyle(
        val icon: AppearanceIcon? = null,
        val accessory: AppearanceIcon? = null,
        /** Null variants use the accent color. */
        val button: AdaptiveColor? = null,
        /** Null variants use the on-accent color. */
        val buttonText: AdaptiveColor? = null,
        /** Before font scaling, for both card icons. */
        val iconSize: Dp? = null,
    )

    companion object {
        /** The app's appearance alone, without remote settings. */
        fun local(appearance: Reception.Appearance): ResolvedAppearance = ResolvedAppearance(
            accent = AdaptiveColor.of(appearance.accentColor),
            onAccent = AdaptiveColor.of(appearance.onAccentColor),
            background = AdaptiveColor.of(appearance.chatBackground),
            incoming = AdaptiveColor.of(appearance.incomingBackground),
            incomingText = AdaptiveColor.of(appearance.incomingForeground),
            title = appearance.title,
            welcomeTitle = appearance.welcomeTitle,
            welcomeText = appearance.welcomeText,
            showsCloseButton = appearance.showsCloseButton,
            preferredColorScheme = appearance.preferredColorScheme,
            closeIcon = appearance.closeIcon,
            closeImage = appearance.closeImageIcon,
            remoteCloseImage = null,
            fontDesign = appearance.fontDesign,
            fontFamily = appearance.fontFamily,
            fadesOlderMessages = appearance.fadesOlderMessages,
            welcomeIcon = appearance.welcomeIcon?.icon,
            welcomeIconColor = AdaptiveColor.of(appearance.welcomeIconColor),
            welcomeIconSize = appearance.welcomeIconSize,
            welcomeIconCornerRadius = clampedRadius(appearance.welcomeIconCornerRadius),
            welcomeIconOffset = appearance.welcomeIconOffset,
            hidesTitle = appearance.hidesTitle,
            hidesWelcomeTitle = appearance.hidesWelcomeTitle,
            hidesWelcomeText = appearance.hidesWelcomeText,
            hidesPushStatus = appearance.hidesPushStatus,
        )

        /** Local radii are clamped to 0–80 dp; a nonfinite value counts as zero. */
        private fun clampedRadius(radius: Dp): Dp =
            if (radius.value.isFinite()) radius.value.coerceIn(0f, 80f).dp else 0.dp
    }
}
