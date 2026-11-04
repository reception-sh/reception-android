package sh.reception.configuration

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import sh.reception.Reception
import sh.reception.ReceptionAppearance
import sh.reception.ReceptionLocalization

/**
 * Applies the explicitly published fields over the app's [local] appearance without changing it. [imageUrl] turns an
 * uploaded image ID into its URL; [languages] are the app language and its supported match, checked in that order.
 */
internal fun RemoteAppearance.applying(
    local: Reception.Appearance,
    imageUrl: (String) -> String? = ReceptionAppearance::imageUrl,
    languages: List<String> = listOf(ReceptionLocalization.appLanguage, ReceptionLocalization.language),
): ResolvedAppearance {
    val base = ResolvedAppearance.local(local)
    fun color(key: String, fallback: AdaptiveColor) = colors?.get(key)?.color(fallback) ?: fallback
    val accent = color("accent", base.accent)
    val onAccent = color("onAccent", base.onAccent)
    val closeIcon = closeIcon?.let { raw -> Reception.CloseIcon.entries.firstOrNull { it.symbol == raw } }
    val design = Reception.FontDesign.entries.firstOrNull { it.raw == fontDesign }
    val hidesWelcomeIcon = isHidden("welcomeIcon")
    // Explicit Show restores the built-in icon over a locally hidden one.
    val localIcon = if (hidesWelcomeIcon == false && base.welcomeIcon == AppearanceIcon.Hidden) null else base.welcomeIcon
    fun card(card: RemoteAppearance.RemoteCard?, name: String) = (card ?: RemoteAppearance.RemoteCard()).let {
        ResolvedAppearance.CardStyle(
            icon = icon(it.icon, it.iconImage, isHidden("${name}Icon"), imageUrl),
            accessory = icon(it.accessory, it.accessoryImage, isHidden("${name}Accessory"), imageUrl),
            button = it.button?.color(accent),
            buttonText = it.buttonText?.color(onAccent),
            iconSize = it.iconSize?.let { size -> size.coerceIn(8.0, 28.0).toFloat().dp },
        )
    }
    return ResolvedAppearance(
        accent = accent,
        onAccent = onAccent,
        background = color("background", base.background),
        incoming = color("incoming", base.incoming),
        incomingText = color("incomingText", base.incomingText),
        title = localized("title", 80, languages) ?: base.title,
        welcomeTitle = localized("welcomeTitle", 160, languages) ?: base.welcomeTitle,
        welcomeText = localized("welcomeText", 1000, languages) ?: base.welcomeText,
        showsCloseButton = showsCloseButton ?: base.showsCloseButton,
        preferredColorScheme = when (colorScheme) {
            "system" -> null
            "light" -> Reception.ColorScheme.LIGHT
            "dark" -> Reception.ColorScheme.DARK
            else -> base.preferredColorScheme
        },
        // A valid remote close symbol replaces a local close image; a remote close image takes precedence over both.
        closeIcon = closeIcon ?: base.closeIcon,
        closeImage = if (closeIcon != null) null else base.closeImage,
        remoteCloseImage = closeImage?.let(imageUrl),
        fontDesign = design ?: base.fontDesign,
        fontFamily = if (design != null) null else base.fontFamily,
        fadesOlderMessages = fadesOlderMessages ?: base.fadesOlderMessages,
        welcomeIcon = icon(welcomeIcon?.symbol, welcomeIcon?.image, hidesWelcomeIcon, imageUrl) ?: localIcon,
        welcomeIconColor = welcomeIcon?.color?.color(base.welcomeIconColor) ?: base.welcomeIconColor,
        welcomeIconSize = welcomeIcon?.size?.let { it.coerceIn(16.0, 160.0).toFloat().dp } ?: base.welcomeIconSize,
        welcomeIconCornerRadius = welcomeIcon?.cornerRadius?.let { it.coerceIn(0.0, 80.0).toFloat().dp }
            ?: base.welcomeIconCornerRadius,
        welcomeIconOffset = welcomeIcon?.offset?.let {
            DpOffset(it.x.coerceIn(-120.0, 120.0).toFloat().dp, it.y.coerceIn(-120.0, 120.0).toFloat().dp)
        } ?: base.welcomeIconOffset,
        hidesTitle = isHidden("title") ?: base.hidesTitle,
        hidesWelcomeTitle = isHidden("welcomeTitle") ?: base.hidesWelcomeTitle,
        hidesWelcomeText = isHidden("welcomeText") ?: base.hidesWelcomeText,
        hidesPushStatus = isHidden("pushStatus") ?: base.hidesPushStatus,
        showsTeamPhotos = showsTeamPhotos ?: false,
        showsTeamNames = showsTeamNames ?: false,
        reviewCard = card(cards?.review, "review"),
        offerCard = card(cards?.offer, "offer"),
        linkCard = card(cards?.link, "link"),
    )
}

/** Explicit visibility overrides the legacy `hidden` list; null inherits the local setting. */
private fun RemoteAppearance.isHidden(element: String): Boolean? {
    visibility?.get(element)?.let { return !it }
    return if (hidden?.contains(element) == true) true else null
}

/** Explicit visibility overrides a legacy "none" symbol; images take precedence over symbols. */
private fun icon(symbol: String?, image: String?, hidden: Boolean?, imageUrl: (String) -> String?): AppearanceIcon? {
    if (hidden == true || (hidden == null && symbol == "none")) return AppearanceIcon.Hidden
    image?.let(imageUrl)?.let { return AppearanceIcon.Remote(it) }
    if (symbol != null && AppearanceSymbols.drawable(symbol) != null) return AppearanceIcon.Symbol(symbol)
    return null
}

/** The app language first, then its supported match, case-insensitively; blank or overlong values are skipped. */
private fun RemoteAppearance.localized(key: String, maximum: Int, languages: List<String>): String? {
    val translations = text?.get(key) ?: return null
    for (language in languages) {
        val value = translations.entries.firstOrNull { it.key.equals(language, ignoreCase = true) }?.value ?: continue
        if (value.isNotBlank() && value.length <= maximum) return value
    }
    return null
}
