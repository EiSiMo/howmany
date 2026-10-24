package run.moritz.howmany.counting

/** The native heap, which the model's memory lives in. */
internal object NativeMemory {
    init {
        System.loadLibrary("memory")
    }

    /**
     * Makes the allocator give memory back to Android as soon as it is freed. Before Android 11 it
     * keeps freed memory, a count's gigabytes, and Android kills apps holding that much even in the
     * foreground. Returns whether the allocator took the setting.
     */
    external fun returnFreedAtOnce(): Boolean
}
