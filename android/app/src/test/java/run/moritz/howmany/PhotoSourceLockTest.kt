package run.moritz.howmany

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhotoSourceLockTest {
    private val lock = PhotoSourceLock()
    private var opened = 0

    private fun open() = lock.open { opened++ }

    @Test
    fun `opens a source`() {
        open()
        assertEquals(1, opened)
    }

    @Test
    fun `further taps open nothing while a source is on its way`() {
        open()
        open()
        lock.open { opened += 10 }
        assertEquals(1, opened)
    }

    @Test
    fun `opens again once the source has handed back`() {
        open()
        lock.release()
        open()
        assertEquals(2, opened)
    }

    @Test
    fun `a source that cannot open blocks nothing`() {
        assertThrows(IllegalStateException::class.java) { lock.open { error("No such app") } }
        open()
        assertEquals(1, opened)
    }
}
