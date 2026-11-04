package sh.reception

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Measurements of the iOS chat in dp (points) and sp (text). Colors and animations live beside it. */
internal object Theme {
    val zero = 0.dp
    val titleChipHorizontal = 16.dp
    val titleChipVertical = 8.dp
    val headerFadeHeight = 80.dp
    val headerFadeOverlap = 64.dp
    val sheetTopInset = 20.dp
    val titleBarHeight = 44.dp
    val sheetGrabberWidth = 36.dp
    val sheetGrabberHeight = 5.dp

    /** The band a drag may close the sheet from: the drag handle inset and the title bar below it. */
    val sheetHandleHeight = sheetTopInset + titleBarHeight
    const val LAYOUT_SETTLE_MILLIS = 250L
    const val AUTOMATION_TYPING_MILLIS = 800L
    val uploadProgressHorizontalInset = 24.dp
    const val SENDING_DOT_RESTING_OPACITY = 0.35f
    const val SENDING_DOT_OPACITY_RANGE = 0.65f
    const val SENDING_OPACITY = 0.6f
    const val FULL_OPACITY = 1f
    val failureIconSize = 20.dp
    val connectionSize = 13.sp
    val sendingDisabledSize = 13.sp
    const val SENDING_DISABLED_LINE_LIMIT = 2
    const val SENDING_DISABLED_MINIMUM_SCALE = 0.5f
    const val CONNECTION_DOT_COUNT = 3
    val connectionDotDiameter = 3.dp
    val connectionDotLift = 2.dp
    val connectionDotSpacing = 3.dp
    val connectionDotTextSpacing = 4.dp
    val typingDotDiameter = 7.dp
    val typingDotSpacing = 4.dp
    val typingDotLift = 2.dp
    const val TYPING_FRAME_INTERVAL = 1.0 / 30
    const val TYPING_RESTING_OPACITY = 0.45f
    const val TYPING_OPACITY_RANGE = 0.45f
    const val TYPING_RESTING_SCALE = 0.9f
    const val TYPING_SCALE_RANGE = 0.1f
    const val TYPING_CYCLE_DURATION = 1.5
    const val TYPING_PULSE_DURATION = 0.7
    const val TYPING_STAGGER = 0.16

    /** 0…1 pulse of typing dot [index] after [elapsed] seconds. */
    fun typingPulse(elapsed: Double, index: Int): Double {
        val phase = (max(0.0, elapsed) + TYPING_CYCLE_DURATION - index * TYPING_STAGGER) % TYPING_CYCLE_DURATION
        if (phase >= TYPING_PULSE_DURATION) return 0.0
        return sin(PI * phase / TYPING_PULSE_DURATION)
    }

    const val CONNECTED_MILLIS = 3_000L
    private const val COUNTDOWN_TICK_MILLIS = 1_000L

    /**
     * Countdowns tick together on whole seconds, so every wait on screen shows the same value.
     * Emits the current wall-clock time in milliseconds right away and then on every whole second.
     */
    fun countdownTicks(now: () -> Long = System::currentTimeMillis): Flow<Long> = flow {
        while (true) {
            val time = now()
            emit(time)
            delay(COUNTDOWN_TICK_MILLIS - time % COUNTDOWN_TICK_MILLIS)
        }
    }

    val deliveryStatusSize = 12.sp
    val deliveryStatusSpacing = 2.dp
    val bubbleRadius = 16.dp
    val bubbleHorizontal = 14.dp
    val bubbleVertical = 10.dp
    val groupedSpacing = 2.dp
    val groupSpacing = 10.dp
    const val TIMESTAMP_INTERVAL_MILLIS = 5 * 60 * 1_000L

    class ComposerMetrics(
        val height: Dp,
        val plus: Dp,
        val sendWidth: Dp,
        val sendHeight: Dp,
        val sendInset: Dp,
        val textLeading: Dp,
        val leading: Dp,
        val trailing: Dp,
        val restingMargin: Dp,
        val keyboardGap: Dp,
        val restingBottom: Dp,
        val spacing: Dp,
    ) {
        val verticalInset: Dp get() = (height - sendHeight) / 2
    }

    // Measured against Messages on iOS 18 (18.2, iPhone 16 Pro, 3×), the flat look that suits Material.
    val composer = ComposerMetrics(
        height = 37.dp, plus = 34.dp, sendWidth = (82f / 3).dp, sendHeight = (82f / 3).dp,
        sendInset = (14f / 3).dp, textLeading = 13.dp, leading = 14.dp, trailing = 16.dp,
        restingMargin = 14.dp, keyboardGap = 14.dp, restingBottom = 8.dp, spacing = 12.dp,
    )
    val composerMessageGap = 16.dp
    val composerMultilineRadius = 18.dp
    const val COMPOSER_LINE_LIMIT = 5
    val unit = 1.dp
    val composerPhotoIcon = 18.dp
    val composerSendIcon = 15.dp
    val composerLineHeight = 21.sp
    val composerMinimumTextWidth = 120.dp
    val composerReferenceWidth = 402.dp
    val bodySize = 17.sp
    val titleSize = 17.sp
    val captionSize = 12.sp
    val timestampSize = 11.sp
    val footnoteSize = 13.sp
    val subheadlineSize = 15.sp
    val dismissSize = 15.sp
    val closeSize = 22.sp
    val emptyIconSize = 44.dp
    val emptyTitleSize = 22.sp
    val emptySubtitleSize = 15.sp
    val emptyBottomInset = 48.dp
    val dismissDiameter = 44.dp
    val toolbarControlDiameter = 44.dp
    val jumpToLatestHeight = 36.dp
    val jumpToLatestTarget = 44.dp
    val jumpToLatestIcon = 13.dp
    const val JUMP_TO_LATEST_MAXIMUM_SCALE = 1.5f
    val jumpToLatestHorizontal = 14.dp
    val jumpToLatestLabelSpacing = 6.dp
    val jumpToLatestGap = 12.dp
    val jumpToLatestRise = 12.dp
    val jumpToLatestShadowRadius = 3.dp
    val jumpToLatestShadowY = 1.dp
    const val JUMP_TO_LATEST_COUNT_LIMIT = 99
    const val MINIMUM_ZOOM = 1f
    const val MAXIMUM_ZOOM = 5f
    const val DOUBLE_TAP_ZOOM = 3f

    /** A drag at 1× this far closes the lightbox; Android only, iOS closes it with the button alone. */
    val lightboxDismissDistance = 120.dp

    /** How much of the black backdrop fades while the photo is dragged away. */
    const val LIGHTBOX_DRAG_FADE = 0.6f
    val small = 8.dp
    val spacing = 16.dp
    val radius = 22.dp
    val control = 48.dp
    val avatarSize = 28.dp
    val cardIconSize = 12.dp

    /**
     * Material Symbols sit in a padded 24-unit box and draw about 15 % smaller than SF Symbols at the same size, so
     * the built-in glyphs of cards, the welcome icon, the close button and the composer's photo button draw this much
     * larger inside their unchanged layout box. Host and remote images keep their size.
     */
    val symbolScale = 1.15f
    val dismissImageSize = 18.dp
    val authorNameSpacing = 4.dp
    const val IMAGE_WIDTH_FRACTION = 0.7f
    val imageMaximumHeight = 320.dp
    val imageBorderWidth = 0.5.dp
    val chip = 56.dp
    val pushPromptSpacing = 4.dp
    val pushPromptVertical = 4.dp
    val pushPromptButtonSpacing = 28.dp

    /** Text buttons keep a usable tap height without looking like filled buttons. */
    val pushPromptButtonHeight = 32.dp
    const val OUTGOING_FADE_STEP = 0.06f
    const val OUTGOING_MAXIMUM_FADE = 0.24f
}
