package com.multistore.app.di

import com.multistore.app.BuildConfig
import com.multistore.core.remoteconfig.IndexUrl
import com.multistore.core.remoteconfig.ParsersKey
import com.multistore.core.model.WebFilterConfig
import com.multistore.core.remoteconfig.ParsersUrl
import com.multistore.core.remoteconfig.RemoteParsers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * What `:core:remoteconfig` cannot know on its own: **where** the document is.
 *
 * Same reason `NetworkModule` gives `:core:network` the cache folder. A library module does not know
 * `BuildConfig`, and the address is a fact of the build.
 *
 * The pinned address stays a single constant, next to the public key; all that is added here is the
 * ability to replace it by passing `-Pmultistore.parsersUrl=…`, which is what allows testing the
 * channel against a document being published rather than against the real one. In every normal build
 * the field is empty and this `@Provides` returns the constant.
 */
@Module
@InstallIn(SingletonComponent::class)
object RemoteConfigAppModule {

    @Provides
    @ParsersUrl
    fun provideParsersUrl(): String =
        BuildConfig.PARSERS_URL_OVERRIDE.ifBlank { ParsersKey.PARSERS_URL }

    /**
     * The index's address, with the same replacement mechanism.
     *
     * `-Pmultistore.indexUrl=…` exists for the same reason as the other one, and with the same limit:
     * the signature stays pinned, so a different address can at most deliver no index at all — never
     * one we did not sign.
     */
    @Provides
    @IndexUrl
    fun provideIndexUrl(): String =
        BuildConfig.INDEX_URL_OVERRIDE.ifBlank { ParsersKey.INDEX_URL }

    /**
     * The WebView filter: compiled defaults plus the signed document's override.
     *
     * It sits here next to the other `@Provides` that go through `RemoteParsers` for the same reason
     * `StoreCatalogTest` demands for store configurations: that merge is what makes a thing
     * **repairable without a release**, and doing it in one place is also how not to forget it.
     *
     * `@Singleton` because `WebFilterConfig` precomputes its two host sets in the constructor:
     * rebuilding it every time the WebView opens would redo that work for nothing.
     */
    @Provides
    @Singleton
    fun provideWebFilter(parsers: RemoteParsers): WebFilterConfig = parsers.webFilter()
}
