package com.multistore.core.remoteconfig.di

import android.content.Context
import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.remoteconfig.IndexFetcher
import com.multistore.core.remoteconfig.IndexUrl
import com.multistore.core.remoteconfig.ParsersFetcher
import com.multistore.core.remoteconfig.ParsersKey
import com.multistore.core.remoteconfig.ParsersUrl
import com.multistore.core.remoteconfig.RemoteConfigFetcher
import com.multistore.core.remoteconfig.RemoteConfigStore
import com.multistore.core.remoteconfig.RemoteIndexStore
import com.multistore.core.remoteconfig.SelfUpdateDownloader
import com.multistore.core.remoteconfig.SelfUpdateSource
import com.multistore.core.remoteconfig.SignedDocuments
import kotlinx.coroutines.CoroutineDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import okhttp3.Call
import okhttp3.OkHttpClient

/** The client the configuration is downloaded with. Qualified: it is not the stores' one. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class RemoteConfigClient

@Module
@InstallIn(SingletonComponent::class)
object RemoteConfigModule {

    /**
     * Where the cached document ends up: `filesDir/remoteconfig`.
     *
     * The constant lives **here** and not in the store, for the same reason the HTTP cache directory
     * lives in `NetworkModule`: it is a decision about where the app writes, not about how the class
     * works. `BackupExclusionTest` reads the line below and demands that directory be excluded from
     * all three sets of backup rules — not because the document is sensitive, it is public and
     * signed, but because that rule has no exceptions.
     *
     * The guardrail also wants the constant declared **in this same file**: that is how it manages
     * to resolve it, and a name arriving from elsewhere makes it fail rather than letting it
     * through. Verified by writing it wrongly the first time.
     */
    @Provides
    @Singleton
    @RemoteConfigDirectory
    fun provideRemoteConfigDirectory(@ApplicationContext context: Context): File =
        File(context.filesDir, REMOTE_CONFIG_DIRECTORY)

    @Provides
    @Singleton
    fun provideSignedDocuments(): SignedDocuments = SignedDocuments(ParsersKey.PUBLIC)

    @Provides
    @Singleton
    fun provideRemoteConfigStore(
        @RemoteConfigDirectory directory: File,
        documents: SignedDocuments,
        clock: Clock,
    ): RemoteConfigStore = RemoteConfigStore(directory, documents, clock)

    /**
     * The index, **with the same key** as `parsers.json`.
     *
     * A second key would be a second thing to guard and to rotate, to protect documents coming out
     * of the same pipeline and signed by the same process. The benefit would exist if the two keys
     * lived in different places — they do not.
     */
    @Provides
    @Singleton
    fun provideRemoteIndexStore(
        @RemoteConfigDirectory directory: File,
        documents: SignedDocuments,
        clock: Clock,
    ): RemoteIndexStore = RemoteIndexStore(directory, documents, clock)

    /**
     * The two fetchers: same class, same client, two documents.
     *
     * The qualifier distinguishes the instances and nothing else. See `SignedDocumentSink` for why
     * they are not two classes.
     */
    @Provides
    @Singleton
    @ParsersFetcher
    fun provideParsersFetcher(
        @RemoteConfigClient calls: okhttp3.Call.Factory,
        store: RemoteConfigStore,
        clock: Clock,
        @IoDispatcher io: CoroutineDispatcher,
        @ParsersUrl url: String,
    ): RemoteConfigFetcher = RemoteConfigFetcher(calls, store, clock, io, url)

    @Provides
    @Singleton
    @IndexFetcher
    fun provideIndexFetcher(
        @RemoteConfigClient calls: okhttp3.Call.Factory,
        store: RemoteIndexStore,
        clock: Clock,
        @IoDispatcher io: CoroutineDispatcher,
        @IndexUrl url: String,
    ): RemoteConfigFetcher = RemoteConfigFetcher(calls, store, clock, io, url)

    // The URL is **not** provided here, and that is not an oversight: it is a fact of the build,
    // like the HTTP cache directory `NetworkModule` provides to `:core:network`. `:app` decides it,
    // being the only one that knows `BuildConfig` — and therefore the only one that can offer a way
    // of pointing the app at a test document without changing the pinned constant.

    /**
     * A client of its own, tiny, with no cache and no interceptors.
     *
     * It deliberately does not reuse the stores'. The per-store client carries that store's
     * User-Agent, its rate limiter and the 50 MB cache: none of the three makes sense for a file of
     * ours, and the cache in particular would be harmful — the document is taken or left, and the
     * date of the file in `filesDir` is enough to decide whether it has changed.
     */
    @Provides
    @Singleton
    @RemoteConfigClient
    fun provideRemoteConfigCallFactory(): Call.Factory = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS.seconds.toJavaDuration())
        .readTimeout(READ_TIMEOUT_SECONDS.seconds.toJavaDuration())
        // An overall timeout, not just per phase: it is background work at startup, and it must not
        // be able to hang on a host that accepts the connection and then does not speak.
        .callTimeout(CALL_TIMEOUT_SECONDS.seconds.toJavaDuration())
        .build()

    /**
     * The real implementation behind the interface. A `@Provides` and not a `@Binds` because this
     * module is an `object`, and mixing the two forms would require two modules.
     */
    @Provides
    @Singleton
    fun provideSelfUpdateSource(downloader: SelfUpdateDownloader): SelfUpdateSource = downloader

    private const val REMOTE_CONFIG_DIRECTORY = "remoteconfig"
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val CALL_TIMEOUT_SECONDS = 30L
}

/** The directory the cached document lives in. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class RemoteConfigDirectory
