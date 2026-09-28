package run.moritz.howmany

import org.junit.Assert.assertEquals
import org.junit.Test

class EasingTest {
    @Test
    fun `inverts easing out`() {
        for (fraction in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
            assertEquals(fraction, inverseEaseOut(easeOut(fraction)), 1e-3f)
        }
    }
}
