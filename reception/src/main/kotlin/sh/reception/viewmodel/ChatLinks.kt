package sh.reception.viewmodel

import sh.reception.Log
import sh.reception.ReceptionLocalization
import sh.reception.ReceptionEvent
import sh.reception.ReceptionPaywalls
import sh.reception.ReceptionSettings
import sh.reception.ReceptionState
import sh.reception.ScreenLink
import sh.reception.client.ActionOutcome
import sh.reception.client.LinkPolicy
import sh.reception.client.recordActionClick
import sh.reception.model.Message
import sh.reception.strings.Strings

// iOS `ChatModel+Links`: screens and links into the host app open after the chat closed, so the host can navigate
// freely. Other links open with the chat left open: web pages in the installed app that claims them, else in a Custom
// Tab over the chat; the rest through the system, like review cards.

/** A link card's URL, when its scheme is one the chat opens. */
internal val Message.linkCardUrl: String?
    get() = linkUrl?.takeIf { kind == "LINK" && LinkPolicy.isAllowed(it) }

/** Whether a link card leads back into the host app. */
internal fun ChatModel.isHostLink(url: String): Boolean = ScreenLink.parse(url) != null || links.isHostScheme(url)

internal fun ChatModel.openLink(url: String, messageId: String) {
    if (links.isClosingForLink) return
    ReceptionState.onEvent?.invoke(ReceptionEvent.LinkOpened)
    when (LinkRoute.of(url, links.isHostScheme, ReceptionSettings.opensWebLinksInApp)) {
        LinkRoute.SCREEN -> closeForLink { openScreen(url, messageId) }
        LinkRoute.HOST -> closeForLink { openExternally(url, messageId) }
        LinkRoute.WEB -> if (openWebLink(url)) recordLinkOutcome(ActionOutcome.OPENED, messageId) else openExternally(url, messageId)
        LinkRoute.SYSTEM -> openExternally(url, messageId)
    }
}

/** A tapped web address in message text: the claiming app or a Custom Tab, or the system. No receipt. */
internal fun ChatModel.openTextLink(url: String) {
    if (!LinkPolicy.isAllowed(url)) return
    val inApp = ReceptionSettings.opensWebLinksInApp && LinkPolicy.isWebLink(url)
    if (!inApp || !openWebLink(url)) links.openExternal(url)
}

internal fun ChatModel.canOpenPaywall(message: Message): Boolean =
    message.kind == "PAYWALL" && ReceptionPaywalls.canOpen(message.paywallId)

internal fun ChatModel.openPaywall(message: Message) {
    val id = message.paywallId ?: return
    val handler = ReceptionPaywalls.onPaywall ?: return
    if (!canOpenPaywall(message)) return
    if (current) session?.recordActionClick(message.id)
    ReceptionState.onEvent?.invoke(ReceptionEvent.PaywallOpened)
    handler(id)
}

/** The review card: the Play Store listing, with the receipt and event before it opens. */
internal fun ChatModel.openReview(url: String, messageId: String) {
    if (current) session?.recordActionClick(messageId)
    ReceptionState.onEvent?.invoke(ReceptionEvent.ReviewOpened)
    links.openExternal(url)
}

/**
 * Runs the host's screen for a screen link. The service only sends one the device reported; a screen the app no longer
 * lists, or one missing its value, counts as failed.
 */
private fun ChatModel.openScreen(url: String, messageId: String) {
    val target = ScreenLink.parse(url) ?: return recordLinkOutcome(ActionOutcome.FAILED, messageId)
    val screen = ScreenLink.usable(ReceptionSettings.screens).firstOrNull { it.id == target.id }
    if (screen == null || (screen.valueName != null && target.value == null)) {
        Log.info { "No screen ${target.id} for this link" }
        return recordLinkOutcome(ActionOutcome.FAILED, messageId)
    }
    screen.open(target.value)
    recordLinkOutcome(ActionOutcome.HANDLED, messageId)
}

private fun ChatModel.closeForLink(next: () -> Unit) {
    links.isClosingForLink = true
    links.closePresentedChat {
        links.isClosingForLink = false
        next()
    }
}

/** An installed app that claims the web link opens it; otherwise a Custom Tab over the chat. */
private fun ChatModel.openWebLink(url: String): Boolean =
    links.openInClaimingApp(url) || links.presentWebLink(url, links.toolbarColor)

private fun ChatModel.openExternally(url: String, messageId: String) {
    recordLinkOutcome(if (links.openExternal(url)) ActionOutcome.OPENED else ActionOutcome.FAILED, messageId)
}

private fun ChatModel.recordLinkOutcome(outcome: ActionOutcome, messageId: String) {
    if (current) session?.recordActionClick(messageId, outcome)
}

/** A card's button text: the agent's label exactly as sent, else the localized default for the card. */
internal val Message.cardLabel: String
    get() = buttonLabel ?: ReceptionLocalization.string(when (kind) {
        "REVIEW" -> Strings.rateOnGooglePlay
        "PAYWALL" -> Strings.viewOffer
        else -> Strings.openLink
    })
