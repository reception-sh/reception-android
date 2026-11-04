package sh.reception.viewmodel

import sh.reception.ReceptionLocalization
import sh.reception.ScreenLink
import sh.reception.client.LinkPolicy
import sh.reception.ui.ChatPresenter
import sh.reception.ui.LinkOpener

/** Where a tapped link goes (SPEC.md "Paywall cards, screens, links"). */
internal enum class LinkRoute {
    /** `reception-screen:` — the chat closes, then the host's screen runs. */
    SCREEN,

    /** A URL the host app itself handles — the chat closes, then the app opens it. */
    HOST,

    /** A web page — a claiming app or a Custom Tab over the open chat. */
    WEB,

    /** Everything else — the system, with the chat left open. */
    SYSTEM;

    companion object {
        fun of(url: String, isHostLink: (String) -> Boolean, opensWebLinksInApp: Boolean): LinkRoute = when {
            ScreenLink.parse(url) != null -> SCREEN
            isHostLink(url) -> HOST
            opensWebLinksInApp && LinkPolicy.isWebLink(url) -> WEB
            else -> SYSTEM
        }
    }
}

/** The platform side of the chat's links, replaced in tests, and the link state of one chat. */
internal class LinkHandling(
    /** Closes the chat the SDK presented, then continues; a host-embedded chat continues at once. */
    val closePresentedChat: (() -> Unit) -> Unit = { next -> ChatPresenter.dismiss(then = next) },
    val openExternal: (String) -> Boolean = LinkOpener::openExternally,
    val openInClaimingApp: (String) -> Boolean = LinkOpener::openInClaimingApp,
    val presentWebLink: (String, Int) -> Boolean = LinkOpener::openCustomTab,
    val isHostScheme: (String) -> Boolean = { url ->
        ReceptionLocalization.context?.let { LinkPolicy.shared(it).isHostScheme(url) } == true
    },
) {
    /** A link tap is closing the chat; further link taps wait until it finished. */
    var isClosingForLink: Boolean = false

    /** The Custom Tab toolbar: the chat's accent color, as the chat last drew it. */
    var toolbarColor: Int = 0
}
