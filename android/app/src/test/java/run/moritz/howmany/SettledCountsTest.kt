package run.moritz.howmany

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettledCountsTest {
    private fun settled(counts: Flow<Int?>, settles: Duration = 1.hours): List<Int> =
        mutableListOf<Int>().also { runBlocking { counts.forEachSettled(settles, it::add) } }

    @Test
    fun `a count that stays is reported once`() {
        val counts = flow {
            emit(3)
            delay(50.milliseconds)
        }
        assertEquals(listOf(3), settled(counts, settles = 1.milliseconds))
    }

    @Test
    fun `a replaced count is dropped`() {
        val counts = flow {
            emit(3)
            emit(4)
            emit(null)
        }
        assertEquals(listOf(4), settled(counts))
    }

    @Test
    fun `stopping reports the count that had not settled yet`() {
        val reported = mutableListOf<Int>()
        runBlocking {
            val collecting = launch {
                flow {
                        emit(3)
                        awaitCancellation()
                    }
                    .forEachSettled(1.hours, reported::add)
            }
            delay(50.milliseconds)
            collecting.cancelAndJoin()
        }
        assertEquals(listOf(3), reported)
    }

    @Test
    fun `moving on reports the count that had not settled yet`() {
        val counts = flow {
            emit(3)
            emit(null)
            delay(1.milliseconds)
        }
        assertEquals(listOf(3), settled(counts))
    }

    @Test
    fun `a settled count is not reported again when moving on`() {
        val counts = flow {
            emit(3)
            delay(50.milliseconds)
            emit(null)
            emit(5)
        }
        assertEquals(listOf(3, 5), settled(counts, settles = 1.milliseconds))
    }
}
