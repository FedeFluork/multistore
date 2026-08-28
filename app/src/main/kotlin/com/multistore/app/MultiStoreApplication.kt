package com.multistore.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The dependency-injection root.
 *
 * It is also the only module that knows the concrete store adapters: the `@IntoSet` multibinding
 * lives in `com.multistore.app.di`, so the core stays unaware of which stores exist.
 *
 * It implements `Configuration.Provider` because the workers have injected dependencies — the
 * `DownloadWorker` needs `DownloadTask` and the notifications — and WorkManager's default factory can
 * only build workers with the two-argument constructor. With the `HiltWorkerFactory` in its place, a
 * `@HiltWorker` gets the rest from the graph.
 *
 * The custom configuration **requires disabling the automatic initialiser**: the manifest removes
 * WorkManager's `androidx.startup` provider, otherwise that one wins the race and our factory is never
 * seen. It is an error that does not show up as an error: it shows up as a worker that never starts,
 * with nothing in the log.
 */
@HiltAndroidApp
class MultiStoreApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    /**
     * Field injection into `Application` happens inside `super.onCreate()`: reading `startup` before
     * that line would give `UninitializedPropertyAccessException`.
     */
    @Inject lateinit var startup: AppStartup

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /**
     * The image loader Coil will use everywhere, taken from the graph.
     *
     * `dagger.Lazy`: `newImageLoader` is called on the **first** image, not at startup, and building it
     * earlier would mean opening a `DiskCache` — that is, reading a journal from disk — during
     * `onCreate`.
     */
    @Inject lateinit var imageLoader: dagger.Lazy<ImageLoader>

    /**
     * Without this line Coil builds an `ImageLoader` of its own with **its** OkHttp and **its** size
     * (2% of the free space), which is exactly what the app used to do. See `ImageModule`.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader.get()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        startup.run()
    }
}
