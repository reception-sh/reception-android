package sh.reception

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.PI

/** SwiftUI's `easeInOut` curve. */
internal val Theme.easeInOut: CubicBezierEasing
    get() = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** A SwiftUI spring of [durationSeconds] and [bounce], in Compose terms. */
internal fun <T> Theme.swiftUISpring(durationSeconds: Float, bounce: Float = 0f): SpringSpec<T> {
    val angularFrequency = 2 * PI.toFloat() / durationSeconds
    return spring(dampingRatio = 1f - bounce, stiffness = angularFrequency * angularFrequency)
}

internal fun <T> Theme.deliveryStatusAnimation(): SpringSpec<T> = swiftUISpring(0.2f)

// Springs, like Messages: a bubble lands with a little give instead of easing to a stop.
internal fun <T> Theme.messageAnimation(): SpringSpec<T> = swiftUISpring(0.3f, bounce = 0.15f)

internal fun <T> Theme.sendButtonAnimation(): SpringSpec<T> = swiftUISpring(0.2f, bounce = 0.15f)

internal fun <T> Theme.sendingDisabledAnimation(): TweenSpec<T> = tween(200, easing = easeInOut)

internal fun <T> Theme.connectionDotAnimation(index: Int): InfiniteRepeatableSpec<T> = infiniteRepeatable(
    animation = tween(600, easing = easeInOut),
    repeatMode = RepeatMode.Reverse,
    initialStartOffset = StartOffset(index * 200),
)

internal const val JUMP_TO_LATEST_SCROLL_MILLIS: Int = 350

internal fun <T> Theme.jumpToLatestScrollAnimation(): TweenSpec<T> =
    tween(JUMP_TO_LATEST_SCROLL_MILLIS, easing = easeInOut)
