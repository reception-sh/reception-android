package sh.reception.ui

import sh.reception.Theme

/**
 * One window's bookkeeping for [KeyboardInsetWatch], free of Android and of the clock (every call says when it
 * happened): when a check for a swallowed keyboard inset is due, whether a check may decide, and that the window is told
 * at most once per keyboard show.
 *
 * A show ends whenever a layout or a check finds the keyboard hidden, before any waiting for the layout to settle, so a
 * quick hide and show is a new show even when no check ran in between. The budget belongs to the window and its show:
 * the chats in one window share it, and focus moving between their composers only decides which chat may be checked.
 * It lives with the window's [EmbeddedKeyboardMode] hold, so when the last chat leaves the window, nobody sees its
 * keyboard any more, and a chat that comes back starts afresh. Main thread only.
 */
internal class KeyboardShows {
    /** The chat whose composer has focus in this window; a window has one focused view at a time. */
    private var focusedChat: Any? = null
    private var changedAt = 0L
    private var reported = false

    /** [chat]'s composer gained or lost focus at [now]. Answers whether to check [chat] once the layout settled. */
    fun focusChanged(chat: Any, focused: Boolean, now: Long): Boolean {
        if (focused) {
            focusedChat = chat
            changedAt = now
        } else if (focusedChat === chat) {
            focusedChat = null
        }
        return focused
    }

    /**
     * The window laid out at [now] with the keyboard [keyboardShown] or not, as [chat] saw it. Answers whether to check
     * [chat] once settled. Every chat sees a hide, but only the focused one, whose check this schedules, moves the
     * settle time: another chat seeing the same layout a moment later would otherwise make that check too early, with
     * nobody left to check again.
     */
    fun laidOut(chat: Any, keyboardShown: Boolean, now: Long): Boolean {
        seen(keyboardShown)
        if (focusedChat !== chat) return false
        changedAt = now
        return true
    }

    /**
     * Whether a check of [chat] at [now] may decide that the window swallowed the keyboard's inset: its composer has
     * focus, the keyboard is shown, nothing changed for [Theme.LAYOUT_SETTLE_MILLIS], and the window was not told in
     * this show yet.
     */
    fun mayCheck(chat: Any, keyboardShown: Boolean, now: Long): Boolean {
        seen(keyboardShown)
        return focusedChat === chat && keyboardShown && !reported && now - changedAt >= Theme.LAYOUT_SETTLE_MILLIS
    }

    /** The window was told in this show. */
    fun reported() {
        reported = true
    }

    private fun seen(keyboardShown: Boolean) {
        if (!keyboardShown) reported = false
    }
}
