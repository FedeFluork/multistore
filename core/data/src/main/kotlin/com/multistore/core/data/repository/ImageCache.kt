package com.multistore.core.data.repository

/**
 * The image cache, as seen by whoever cannot see Coil.
 *
 * ### Why it is an interface here instead of a dependency
 *
 * `:core:data` is the repositories' module and sees neither Compose nor Coil; the `ImageLoader`, on
 * the other hand, is built in `:app`, which is the dependency-injection root and is also the only
 * place where `SingletonImageLoader.Factory` can be implemented. The arrow between the two modules
 * points the wrong way for a direct call, and it is the same situation already solved this way for
 * `DownloadTask` between `:core:data` and `:core:download`.
 *
 * ### Why only two operations
 *
 * The only two the Settings screen has to be able to perform on a cache layer: say how much it
 * occupies, and empty it. The **cap** is deliberately not here — it is fixed when the `DiskCache` is
 * constructed, so it is not an operation but a parameter, and it goes through the DataStore like
 * every other setting.
 */
interface ImageCache {

    /** How much it takes on disk, right now. */
    suspend fun sizeBytes(): Long

    /**
     * Empties disk **and** memory.
     *
     * Both, because "empty" has a single meaning to whoever presses: leaving the memory cache in
     * place would keep the same icons on screen after the operation, and the only proof anything had
     * happened would be the number.
     */
    suspend fun clear()

    companion object {
        /**
         * What answers where an `ImageLoader` does not exist: the tests, and any build not wiring
         * `:app`.
         *
         * Zero bytes and no operation is the right answer and not a fallback: if there is no image
         * loader, there is no image cache to empty.
         */
        val NONE: ImageCache = object : ImageCache {
            override suspend fun sizeBytes(): Long = 0
            override suspend fun clear() = Unit
        }
    }
}
