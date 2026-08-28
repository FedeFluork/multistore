package com.multistore.app.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.data.repository.ImageCache
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.network.http.StoreHttpClients
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/**
 * The image loader, which used not to exist.
 *
 * `coil-network-okhttp` had been on the classpath from the start and nobody configured an
 * `ImageLoader`: Coil therefore used its own singleton, with its own `OkHttpClient()` and its own
 * cache. On the device it was found where Coil puts it by itself — `cache/coil3_disk_cache`, 177
 * files, 4.3 MB — next to our `cache/http`. The two things that misalignment cost:
 *
 *  - **a second connection pool** towards hosts that already had one. `f-droid.org` serves both the
 *    pages and the icons, so the two-requests-per-host cap `StoreHttpClients` applies out of courtesy
 *    was the cap of *one half* of the traffic, not of all of it;
 *  - **a size nobody had chosen.** Coil's default is **2% of the free space**: on the emulator, with
 *    3,184,072 KB free, about 62 MB; on a phone with 200 GB free, four gigabytes. It is now ~200 MB —
 *    and configurable.
 *
 * What it did **not** cost, and this is worth writing because one might assume otherwise: the
 * User-Agent. See the measurement in `StoreHttpClients.imageClient`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        clients: StoreHttpClients,
        settings: SettingsRepository,
    ): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { clients.imageClient(IMAGE_USER_AGENT) })) }
        // The block is a **lazy initialiser**: Coil runs it on the first image, on its own fetch
        // dispatcher and not on the main thread. That is what makes reading the DataStore blockingly
        // here legitimate, and it is also the only way to read it without races: capturing the value
        // when the graph is built would mean a window in which the DataStore has not emitted yet, that
        // is a user-chosen cap silently ignored on the very launch it was meant to apply to.
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(IMAGE_CACHE_DIRECTORY).toOkioPath())
                .maxSizeBytes(runBlocking { settings.storage.first() }.imageCacheMaxBytes)
                .build()
        }
        .crossfade(true)
        .build()

    @Provides
    @Singleton
    fun provideImageCache(
        loader: dagger.Lazy<ImageLoader>,
        @IoDispatcher io: CoroutineDispatcher,
    ): ImageCache = CoilImageCache(loader, io)

    /**
     * The image cache folder.
     *
     * Ours and not the one Coil picks by itself (`coil3_disk_cache`): a folder we declare is a folder
     * we know how to measure and empty even the day the library changes its default. It sits in
     * `cacheDir` and not in `filesDir` because it is real cache — the system may take it away when
     * space runs out, and rightly so: an icon gets re-downloaded, not a verified APK.
     */
    private const val IMAGE_CACHE_DIRECTORY = "images"

    /**
     * The images' User-Agent.
     *
     * One and not nine: an icon is a subresource, and a browser loading a page does not change UA
     * between the document and its images. **None of the six hosts the nine stores' icons come from
     * requires it** — measured with OkHttp, 200 with no UA at all on all six — so this constant is
     * common practice, not a defence: see the table in `StoreHttpClients.imageClient`.
     */
    private const val IMAGE_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
}

/**
 * [ImageCache] on top of Coil.
 *
 * `dagger.Lazy` and not the `ImageLoader` directly: asking for the cache size must not be the reason
 * the loader gets built. The Settings screen is often opened without any image ever having been
 * requested, and building it there would mean opening a `DiskCache` — that is, reading a journal from
 * disk — in order to answer "zero".
 */
private class CoilImageCache(
    private val loader: dagger.Lazy<ImageLoader>,
    private val io: CoroutineDispatcher,
) : ImageCache {

    override suspend fun sizeBytes(): Long = withContext(io) {
        loader.get().diskCache?.size ?: 0L
    }

    /**
     * Empties disk **and** memory.
     *
     * The second half is not zeal: without it the already-decoded icons would stay on screen and the
     * only evidence the operation happened would be the changed number — that is, a button that looks
     * like it did nothing, which is also the quickest way to get it pressed twice.
     */
    override suspend fun clear() {
        withContext(io) {
            val imageLoader = loader.get()
            imageLoader.diskCache?.clear()
            imageLoader.memoryCache?.clear()
        }
    }
}
