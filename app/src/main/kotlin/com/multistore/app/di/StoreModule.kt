package com.multistore.app.di

import com.multistore.core.model.StoreId
import com.multistore.core.remoteconfig.RemoteParsers
import com.multistore.store.api.StoreAdapter
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.apkcombo.ApkComboStoreAdapter
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.apkmirror.ApkMirrorStoreAdapter
import com.multistore.store.an1.An1Config
import com.multistore.store.an1.An1StoreAdapter
import com.multistore.store.apkmody.ApkModyConfig
import com.multistore.store.apkmody.ApkModyStoreAdapter
import com.multistore.store.modyolo.ModyoloConfig
import com.multistore.store.modyolo.ModyoloStoreAdapter
import com.multistore.store.liteapks.LiteapksConfig
import com.multistore.store.liteapks.LiteapksStoreAdapter
import com.multistore.store.pdalife.PdalifeConfig
import com.multistore.store.pdalife.PdalifeStoreAdapter
import com.multistore.store.fdroid.FdroidConfig
import com.multistore.store.fdroid.FdroidStoreAdapter
import com.multistore.store.uptodown.UptodownConfig
import com.multistore.store.uptodown.UptodownStoreAdapter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * The only place in the project allowed to name a concrete store.
 *
 * Dependency rules: `:core:*` and `:feature:*` NEVER depend on a concrete `:store:<name>`. Concrete
 * adapters are wired exclusively in `:app` via Hilt `@IntoSet`. The `checkDependencyRules` task
 * verifies the first half of that sentence; this file is the second.
 *
 * There was once only one, F-Droid, and the choice was deliberate: the only one of the nine with a
 * signed index, a hash on every version and a one-hop download. If the critical path does not hold up
 * with it, it holds up with none.
 *
 * **The way the others were added is the result that counts.** Each is one `@Binds` line and one
 * configuration `@Provides`, and **no file in `:core:*` or `:feature:*` was touched to make them
 * work**. That was the promise — no step requires touching the core — and with a single implementer
 * it was only a hope.
 *
 * The tightest proof is uptodown, because it is the one least like the others: its download requires
 * a human gesture, an app's identity lives in a **subdomain** rather than in a path, and the page
 * publishes a hash but no `versionCode`. The contract allowed for all three from the start —
 * `DownloadMode.USER_ASSISTED_ONLY`, an opaque `StoreAppRef`, a nullable `versionCode` — and none of
 * the three was altered.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StoreModule {

    @Binds
    @IntoSet
    abstract fun bindFdroidStoreAdapter(adapter: FdroidStoreAdapter): StoreAdapter

    @Binds
    @IntoSet
    abstract fun bindApkComboStoreAdapter(adapter: ApkComboStoreAdapter): StoreAdapter

    @Binds
    @IntoSet
    abstract fun bindApkMirrorStoreAdapter(adapter: ApkMirrorStoreAdapter): StoreAdapter

    @Binds
    @IntoSet
    abstract fun bindApkModyStoreAdapter(adapter: ApkModyStoreAdapter): StoreAdapter

    @Binds
    @IntoSet
    abstract fun bindUptodownStoreAdapter(adapter: UptodownStoreAdapter): StoreAdapter

    @Binds
    @IntoSet
    abstract fun bindModyoloStoreAdapter(adapter: ModyoloStoreAdapter): StoreAdapter

    @Binds
    @IntoSet
    abstract fun bindAn1StoreAdapter(adapter: An1StoreAdapter): StoreAdapter

    @Binds
    @IntoSet
    abstract fun bindPdalifeStoreAdapter(adapter: PdalifeStoreAdapter): StoreAdapter

    @Binds
    @IntoSet
    abstract fun bindLiteapksStoreAdapter(adapter: LiteapksStoreAdapter): StoreAdapter

    companion object {
        /**
         * The compiled defaults, with the remote override on top if the signature is valid.
         *
         * **It is the remote-config exit criterion in five lines, repeated five times.** Remote config
         * is an override, never the only source; a change of selector or of domain is repaired by
         * publishing `parsers.json`, with no app release. This is the point — the only one — where the
         * two meet.
         *
         * That it is a single line per store is an effect of the adapters' design, not a merit of this
         * class: an adapter **receives** its configuration instead of reading it, so interposing
         * something between the constructor and whoever uses it requires no change to the adapter. Had
         * it been the other way round, remote config would have been a change to five modules the
         * contract says must not be touched.
         *
         * The explicit `KSerializer` is not noise: it is what turns a forgotten `@Serializable` on a
         * configuration into a **compile error** instead of a crash on first launch. See the note in
         * `RemoteParsers` — it happened, on F-Droid.
         *
         * `RemoteParsers.override` never throws and cannot produce a half configuration: with no
         * document, with a document that does not name this store, or with an override that does not
         * hold, it returns the identical default. A remote configuration that could make startup fail
         * would be a way of breaking the app remotely, that is the opposite of what it exists for.
         */
        @Provides
        @Singleton
        fun provideFdroidConfig(parsers: RemoteParsers): FdroidConfig =
            parsers.override(StoreId.FDROID, FdroidConfig(), FdroidConfig.serializer())

        @Provides
        @Singleton
        fun provideApkComboConfig(parsers: RemoteParsers): ApkComboConfig =
            parsers.override(StoreId.APKCOMBO, ApkComboConfig(), ApkComboConfig.serializer())

        @Provides
        @Singleton
        fun provideApkMirrorConfig(parsers: RemoteParsers): ApkMirrorConfig =
            parsers.override(StoreId.APKMIRROR, ApkMirrorConfig(), ApkMirrorConfig.serializer())

        @Provides
        @Singleton
        fun provideApkModyConfig(parsers: RemoteParsers): ApkModyConfig =
            parsers.override(StoreId.APKMODY, ApkModyConfig(), ApkModyConfig.serializer())

        @Provides
        @Singleton
        fun provideUptodownConfig(parsers: RemoteParsers): UptodownConfig =
            parsers.override(StoreId.UPTODOWN, UptodownConfig(), UptodownConfig.serializer())

        @Provides
        @Singleton
        fun provideModyoloConfig(parsers: RemoteParsers): ModyoloConfig =
            parsers.override(StoreId.MODYOLO, ModyoloConfig(), ModyoloConfig.serializer())

        @Provides
        @Singleton
        fun provideAn1Config(parsers: RemoteParsers): An1Config =
            parsers.override(StoreId.AN1, An1Config(), An1Config.serializer())

        @Provides
        @Singleton
        fun providePdalifeConfig(parsers: RemoteParsers): PdalifeConfig =
            parsers.override(StoreId.PDALIFE, PdalifeConfig(), PdalifeConfig.serializer())

        @Provides
        @Singleton
        fun provideLiteapksConfig(parsers: RemoteParsers): LiteapksConfig =
            parsers.override(StoreId.LITEAPKS, LiteapksConfig(), LiteapksConfig.serializer())
    }
}
