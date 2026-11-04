package sh.reception.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import sh.reception.model.Message
import sh.reception.model.MessageStatus
import sh.reception.viewmodel.RetryAvailability

/**
 * The "Not delivered" line with Retry or the automatic countdown and Cancel composes on whatever Compose the host
 * resolves (it once called a `FlowRow` overload that Compose 1.8 removed), and wraps like iOS's `ViewThatFits`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryStatusTest {
    private fun compose(content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { ChatRoot(embedded = true, content) }
        ShadowLooper.idleMainLooper()
    }

    private val failed = Message(id = "m1", sender = "USER", text = "Hello", attachments = emptyList(),
        createdAt = 0, status = MessageStatus.FAILED)

    @Test
    fun aFailedMessageWithRetryComposesAndLaysOut() {
        var placed = false
        compose {
            Box(Modifier.onPlaced { placed = true }) {
                DeliveryStatus(failed, showsDeliveryStatus = true, RetryAvailability.Now, retry = {}, cancelRetry = {})
            }
        }
        assertTrue(placed)
    }

    @Test
    fun aFailedMessageWithItsAutomaticRetryComposesAndLaysOut() {
        var placed = false
        val until = System.currentTimeMillis() + 60_000
        compose {
            Box(Modifier.width(120.dp).onPlaced { placed = true }) {
                DeliveryStatus(failed, showsDeliveryStatus = true, RetryAvailability.Automatic(until), retry = {},
                    cancelRetry = {})
            }
        }
        assertTrue(placed)
    }

    private fun positions(width: Int): List<IntOffset> {
        val positions = MutableList(3) { IntOffset.Zero }
        compose {
            Box(Modifier.width(width.dp)) {
                FittingRow(rowSpacing = 8.dp, columnSpacing = 2.dp) {
                    listOf(30, 20, 10).forEachIndexed { index, size ->
                        Box(Modifier.size(size.dp).onPlaced {
                            val offset = it.positionInParent()
                            positions[index] = IntOffset(offset.x.toInt(), offset.y.toInt())
                        })
                    }
                }
            }
        }
        return positions
    }

    @Test
    fun childrenThatFitSitInOneCenteredRow() {
        // Robolectric's default density is 1, so dp and px agree.
        assertEquals(listOf(IntOffset(0, 0), IntOffset(38, 5), IntOffset(66, 10)), positions(width = 76))
    }

    @Test
    fun childrenThatDoNotFitStackAtTheEnd() {
        assertEquals(listOf(IntOffset(0, 0), IntOffset(10, 32), IntOffset(20, 54)), positions(width = 75))
    }
}
