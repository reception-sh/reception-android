package sh.reception.ui

import androidx.compose.ui.unit.Dp
import sh.reception.Theme

/**
 * The composer's measurements for a width and text scale: vertical sizes follow the text scale like Dynamic Type;
 * horizontal ones shrink when the controls would leave less than the minimum text width on a narrow screen.
 */
internal class ComposerDimensions private constructor(
    private val scale: Float,
    private val horizontal: Float,
    private val keyboard: Boolean,
) {
    private val base = Theme.composer
    val height: Dp get() = base.height * scale
    val photo: Dp get() = base.plus * horizontal
    val photoIcon: Dp get() = Theme.composerPhotoIcon * horizontal
    val sendWidth: Dp get() = base.sendWidth * horizontal
    val sendHeight: Dp get() = base.sendHeight * horizontal
    val sendIcon: Dp get() = Theme.composerSendIcon * horizontal
    val vertical: Dp get() = base.verticalInset * scale
    val innerHeight: Dp get() = height - vertical * 2
    val lineHeight: Dp get() = Dp(Theme.composerLineHeight.value) * scale
    val leading: Dp get() = (if (keyboard) base.leading else base.restingMargin) * horizontal
    val trailing: Dp get() = base.trailing * horizontal
    val bottom: Dp get() = (if (keyboard) base.keyboardGap else base.restingBottom) * scale
    val gap: Dp get() = base.spacing * horizontal
    val small: Dp get() = Theme.small * horizontal
    val textLeading: Dp get() = base.textLeading * horizontal
    val sendInset: Dp get() = base.sendInset * horizontal
    val radius: Dp get() = Theme.composerMultilineRadius * scale
    val chip: Dp get() = Theme.chip * horizontal

    /** How far a button of [diameter] sits above the field's bottom so it centers on the first or only line. */
    fun buttonBottom(diameter: Dp, multiline: Boolean): Dp = ((if (multiline) lineHeight else innerHeight) - diameter) / 2

    companion object {
        fun resolve(width: Dp, keyboard: Boolean, scale: Float): ComposerDimensions {
            val base = Theme.composer
            // Keep usable text width when a large text scale would make the controls exceed a phone's width.
            val budget = base.restingMargin * 2 + base.plus + base.spacing + base.textLeading + base.sendWidth +
                base.sendInset + Theme.small + Theme.composerMinimumTextWidth
            return ComposerDimensions(scale, minOf(scale, width / budget), keyboard)
        }
    }
}
