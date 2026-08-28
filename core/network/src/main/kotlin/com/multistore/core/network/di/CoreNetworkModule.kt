package com.multistore.core.network.di

import com.multistore.core.network.cookie.ClearanceCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The cookie jar, one per process.
 *
 * It is here rather than in `:app` for a reason of direction: rung 3 lives in `:core:challenge`
 * and needs **this same** jar to deliver what the WebView obtains. Were `:app` to provide it, a
 * `:core:*` module would depend on a binding declared in the application module — the arrow would
 * point upwards.
 *
 * `@Singleton` is not decoration: two instances would mean the WebView deposits the
 * `cf_clearance` in one jar and OkHttp reads from the other. The symptom would be a rung 3 that
 * always succeeds and never unblocks anything.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreNetworkModule {

    @Provides
    @Singleton
    fun provideClearanceCookieJar(): ClearanceCookieJar = ClearanceCookieJar()
}
