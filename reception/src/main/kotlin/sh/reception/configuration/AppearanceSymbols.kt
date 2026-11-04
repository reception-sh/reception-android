package sh.reception.configuration

import androidx.annotation.DrawableRes
import sh.reception.R

/**
 * The SF Symbols names the dashboard and the SDK use, drawn with the closest Material Symbol (Rounded; filled where
 * the SF Symbol is). Covers every name in the dashboard's `appearance-symbols.ts` plus the chat's built-in icons.
 */
internal object AppearanceSymbols {
    private val drawables: Map<String, Int> = mapOf(
        // Welcome icons.
        "bubble.left.and.bubble.right" to R.drawable.reception_symbol_forum,
        "bubble.left.fill" to R.drawable.reception_symbol_chat_bubble,
        "message.fill" to R.drawable.reception_symbol_chat,
        "questionmark.bubble" to R.drawable.reception_symbol_contact_support,
        "envelope.fill" to R.drawable.reception_symbol_mail,
        "lifepreserver" to R.drawable.reception_symbol_support,
        "sparkles" to R.drawable.reception_symbol_auto_awesome,
        "heart.fill" to R.drawable.reception_symbol_favorite,
        "hand.wave.fill" to R.drawable.reception_symbol_waving_hand,
        "person.2.fill" to R.drawable.reception_symbol_group,
        // Card icons.
        "star.fill" to R.drawable.reception_symbol_star,
        "gift.fill" to R.drawable.reception_symbol_redeem,
        "hand.thumbsup.fill" to R.drawable.reception_symbol_thumb_up,
        "crown.fill" to R.drawable.reception_symbol_workspace_premium,
        "tag.fill" to R.drawable.reception_symbol_sell,
        "percent" to R.drawable.reception_symbol_percent,
        "bolt.fill" to R.drawable.reception_symbol_bolt,
        "link" to R.drawable.reception_symbol_link,
        // Card accessories.
        "chevron.right" to R.drawable.reception_symbol_chevron_right,
        "arrow.up.right" to R.drawable.reception_symbol_arrow_outward,
        "arrow.right" to R.drawable.reception_symbol_arrow_forward,
        // Built-in chat icons.
        "xmark" to R.drawable.reception_symbol_close,
        "xmark.circle.fill" to R.drawable.reception_symbol_cancel,
        "chevron.down" to R.drawable.reception_symbol_keyboard_arrow_down,
        "arrow.down" to R.drawable.reception_symbol_arrow_downward,
        "arrow.up" to R.drawable.reception_symbol_arrow_upward,
        "bell.fill" to R.drawable.reception_symbol_notifications,
        "photo.on.rectangle" to R.drawable.reception_symbol_add_photo_alternate,
        "exclamationmark.circle" to R.drawable.reception_symbol_error,
        "exclamationmark.circle.fill" to R.drawable.reception_symbol_error_filled,
        "clock" to R.drawable.reception_symbol_schedule,
        "wifi" to R.drawable.reception_symbol_wifi,
        "wifi.slash" to R.drawable.reception_symbol_wifi_off,
        "pause.circle" to R.drawable.reception_symbol_pause_circle,
    )

    /** The drawable for an SF Symbols name; null for a name the SDK cannot draw, like iOS for an unknown symbol. */
    @DrawableRes
    fun drawable(name: String): Int? = drawables[name]
}
