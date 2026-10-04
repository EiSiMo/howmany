package run.moritz.howmany

import org.junit.Assert.assertEquals
import org.junit.Test
import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.Point

class ExemplarsTest {
    private val big = Box(0f, 0f, 100f, 100f)
    private val small = Box(40f, 40f, 60f, 60f)
    private val far = Box(200f, 200f, 240f, 240f)

    @Test
    fun `adds exemplars only up to the maximum`() {
        var exemplars = emptyList<Box>()
        repeat(MAX_EXEMPLARS + 1) {
            exemplars = exemplars.marked(Box(it * 10f, 0f, it * 10f + 5f, 5f))
        }

        assertEquals(MAX_EXEMPLARS, exemplars.size)
    }

    @Test
    fun `removes the smallest exemplar under the tap`() {
        assertEquals(listOf(big, far), listOf(big, small, far).removedAt(Point(50f, 50f)))
    }

    @Test
    fun `removes the last drawn of same-sized exemplars under the tap`() {
        val first = Box(10f, 10f, 20f, 20f)
        val second = Box(10f, 10f, 20f, 20f)

        assertEquals(listOf(first), listOf(first, second).removedAt(Point(15f, 15f)))
    }

    @Test
    fun `removes nothing when the tap misses every exemplar`() {
        assertEquals(listOf(big, small), listOf(big, small).removedAt(Point(500f, 500f)))
    }
}
