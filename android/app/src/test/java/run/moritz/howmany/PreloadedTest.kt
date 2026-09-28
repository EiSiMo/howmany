package run.moritz.howmany

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadedTest {
    private class Resource : AutoCloseable {
        @Volatile var closed = false

        override fun close() {
            closed = true
        }
    }

    /** A slow factory that finishes only when released. */
    private class SlowFactory {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        @Volatile var created: Resource? = null

        fun create(): Resource {
            calls.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Never released" }
            return Resource().also { created = it }
        }
    }

    @Test
    fun `starts creating before anyone asks`() {
        val factory = SlowFactory()

        Preloaded(factory::create).use {
            assertTrue(factory.started.await(5, TimeUnit.SECONDS))
            factory.release.countDown()
        }
    }

    @Test
    fun `waits for the ongoing creation instead of starting another`() = runBlocking {
        val factory = SlowFactory()
        Preloaded(factory::create).use { preloaded ->
            val gets = List(3) { async { preloaded.get() } }
            factory.started.await(5, TimeUnit.SECONDS)
            assertFalse(gets.any { it.isCompleted })

            factory.release.countDown()
            val values = gets.awaitAll()

            values.forEach { assertSame(values.first(), it) }
            assertEquals(1, factory.calls.get())
        }
    }

    @Test
    fun `rethrows the creation error to everyone who asks`() = runBlocking {
        val preloaded = Preloaded<Resource> { throw IllegalStateException("broken model") }

        repeat(2) {
            val error =
                assertThrows(IllegalStateException::class.java) { runBlocking { preloaded.get() } }
            assertEquals("broken model", error.message)
        }
    }

    @Test
    fun `closes the value, even when closed while still creating`() = runBlocking {
        val factory = SlowFactory()
        val preloaded = Preloaded(factory::create)
        factory.started.await(5, TimeUnit.SECONDS)

        preloaded.close()
        factory.release.countDown()

        withTimeout(5.seconds) { while (factory.created?.closed != true) delay(10) }
    }
}
