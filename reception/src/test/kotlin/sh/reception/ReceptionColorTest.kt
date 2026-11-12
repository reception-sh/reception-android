package sh.reception

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** `argb` gives the light and dark variants as Compose colors; one value serves both modes. */
class ReceptionColorTest {
    @Test
    fun argbIntsBecomeTheLightAndDarkVariants() {
        val pair = ReceptionColor.argb(0xFF5B4FE0.toInt(), 0xFFB0A8FF.toInt()).adaptive(null)
        assertEquals(Color(0xFF5B4FE0), pair.light)
        assertEquals(Color(0xFFB0A8FF), pair.dark)
        val single = ReceptionColor.argb(0xFF2E7D32.toInt()).adaptive(null)
        assertEquals(Color(0xFF2E7D32), single.light)
        assertEquals(Color(0xFF2E7D32), single.dark)
    }
}
