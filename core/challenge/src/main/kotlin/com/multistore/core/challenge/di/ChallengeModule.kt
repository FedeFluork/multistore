package com.multistore.core.challenge.di

import android.content.Context
import com.multistore.core.challenge.AndroidWebViewChallengeEngine
import com.multistore.core.challenge.SilentChallengeEngine
import com.multistore.core.challenge.WebViewSilentResolver
import com.multistore.core.network.challenge.ChallengeResolver
import com.multistore.core.network.cookie.ClearanceCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * The silent WebView rung enters the ladder without anyone going to look for it.
 *
 * `@IntoSet` and not a parameter: `StoreHttpClients` receives the set of rungs this build has and
 * assembles the ladder itself. In a JVM test — and in any module that does not see Android — the set
 * is empty and the ladder shortens to the two network rungs, with no conditional branches and without
 * its user having to know which of the two they are getting.
 *
 * It is also why adding Cronet, if a measurement one day justified it, would be one more `@Provides`
 * in here and nothing else.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChallengeModule {

    @Provides
    @Singleton
    fun provideSilentChallengeEngine(
        @ApplicationContext context: Context,
    ): SilentChallengeEngine = AndroidWebViewChallengeEngine(context)

    @Provides
    @Singleton
    @IntoSet
    fun provideWebViewSilentResolver(
        engine: SilentChallengeEngine,
        cookies: ClearanceCookieJar,
    ): ChallengeResolver = WebViewSilentResolver(engine, cookies)
}
