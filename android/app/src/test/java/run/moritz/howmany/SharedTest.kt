package run.moritz.howmany

import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SharedTest {
    private class Resource : AutoCloseable {
        @Volatile var closed = false

        override fun close() {
            closed = true
        }
    }

    private val created = AtomicInteger()
    private val shared = Shared {
        created.incrementAndGet()
        Resource()
    }

    @Test
    fun `everyone holding it at once gets the same value`() = runBlocking {
        val first = shared.take()
        val second = shared.take()

        assertSame(first.get(), second.get())
        assertEquals(1, created.get())
    }

    @Test
    fun `stays open while anyone still holds it`() = runBlocking {
        val first = shared.take()
        val second = shared.take()
        val value = second.get()

        first.close()
        delay(100)

        assertFalse(value.closed)
        second.close()
        withTimeout(5.seconds) { while (!value.closed) delay(10) }
    }

    @Test
    fun `creates it anew once everyone let go`() = runBlocking {
        val first = shared.take()
        val value = first.get()
        first.close()

        assertNotSame(value, shared.take().get())
    }

    @Test
    fun `letting go twice counts once`() = runBlocking {
        val first = shared.take()
        val second = shared.take()
        val value = second.get()

        first.close()
        first.close()
        delay(100)

        assertFalse(value.closed)
    }
}
