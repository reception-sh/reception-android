package sh.reception

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeTest {
    @Test
    fun countdownTicksAlignToWholeSeconds() = runTest {
        val start = 1_700_000_000_250L
        val ticks = Theme.countdownTicks { start + currentTime }.take(3).toList()
        assertEquals(listOf(start, 1_700_000_001_000L, 1_700_000_002_000L), ticks)
    }
}
