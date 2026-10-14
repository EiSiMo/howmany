package run.moritz.howmany

/**
 * A value [Preloaded] once for everyone holding it at the same time: created when the first [take]s
 * it, closed when the last lets go of it.
 */
class Shared<T : AutoCloseable>(private val create: () -> T) {
    private val lock = Any()
    private var value: Preloaded<T>? = null
    private var holders = 0

    /** Holds the value, starting its creation if no one else holds it. Close to let go. */
    fun take(): Lease<T> =
        synchronized(lock) {
            val held = value ?: Preloaded(create).also { value = it }
            holders++
            Lease(held, ::letGo)
        }

    private fun letGo() {
        synchronized(lock) {
            if (--holders > 0) return
            value?.close()
            value = null
        }
    }

    /** A hold on the shared value. */
    class Lease<T : AutoCloseable>
    internal constructor(private val value: Preloaded<T>, private val letGo: () -> Unit) :
        AutoCloseable {
        private var closed = false

        /** Returns the value, waiting for its creation if needed; rethrows its creation error. */
        suspend fun get(): T = value.get()

        /** Lets go of the value. Don't [get] it afterwards. */
        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            letGo()
        }
    }
}
