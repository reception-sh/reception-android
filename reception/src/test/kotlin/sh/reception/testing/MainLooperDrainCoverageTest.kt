package sh.reception.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A Robolectric test class without [MainLooperDrain] can stop Compose for every later test of its sandbox. */
class MainLooperDrainCoverageTest {
    @Test
    fun everyRobolectricTestClassDrainsTheMainLooper() {
        val sources = File("src/test/kotlin").walk().filter { it.extension == "kt" }.toList()
        assertTrue("test sources found from ${File("").absolutePath}", sources.isNotEmpty())
        val missing = sources.filter { "RobolectricTestRunner::class" in it.readText() && "MainLooperDrain()" !in it.readText() }
        assertEquals(emptyList<String>(), missing.map { it.name })
    }
}
