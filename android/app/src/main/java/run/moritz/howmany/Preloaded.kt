package run.moritz.howmany

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * A value that is expensive to create, created once in the background as soon as this is
 * constructed, so it is ready (or nearly) when first needed.
 */
class Preloaded<T : AutoCloseable>(create: () -> T) : AutoCloseable {
    // Its own scope: cancelling could not stop a blocking creation anyway, and would lose the
    // value it creates, which then could not be closed.
    private val value: Deferred<T> = CoroutineScope(Dispatchers.IO).async { create() }

    /** Returns the value, waiting for its creation if needed; rethrows its creation error. */
    suspend fun get(): T = value.await()

    /** Closes the value, now or once created. Don't [get] it afterwards. */
    override fun close() {
        value.invokeOnCompletion { error -> if (error == null) value.getCompleted().close() }
    }
}
