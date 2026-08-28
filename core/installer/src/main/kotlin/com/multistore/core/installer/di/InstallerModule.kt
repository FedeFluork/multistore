package com.multistore.core.installer.di

import android.content.Context
import com.multistore.core.installer.Installer
import com.multistore.core.installer.container.ContainerReader
import com.multistore.core.installer.container.ZipContainerReader
import com.multistore.core.installer.session.SessionInstaller
import com.multistore.core.installer.shell.RootShell
import com.multistore.core.installer.shell.ShellInstaller
import com.multistore.core.installer.shell.ShizukuShell
import com.multistore.core.installer.verify.ApkArchiveReader
import com.multistore.core.installer.verify.ApksigApkArchiveReader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class InstallerModule {

    @Binds
    @IntoSet
    abstract fun bindSessionInstaller(installer: SessionInstaller): Installer

    /**
     * Whoever looks inside a downloaded file.
     *
     * It is an interface because the decision "this is a container" and the decision "what do I
     * install from it" are only provable by separating them from whoever opens the real zips — but
     * the implementation deciding whether an installation starts is a single one.
     */
    @Binds
    abstract fun bindContainerReader(reader: ZipContainerReader): ContainerReader

    companion object {
        /**
         * The APK reader, with the project's `minSdk`.
         *
         * The value decides which signature schemes `apksig` considers sufficient: declaring one
         * higher than the real one would mean accepting APKs that would not install on older
         * devices.
         */
        @Provides
        @Singleton
        fun provideApkArchiveReader(): ApkArchiveReader = ApksigApkArchiveReader()

        /**
         * The two silent installers.
         *
         * They are `@Provides` and not `@Binds` because they are **the same** [ShellInstaller] with
         * two different shells: what distinguishes them is how the process is born, not what they do
         * inside it. Two empty subclasses would exist only to keep Hilt's graph happy.
         *
         * The package name comes from the context and not from an injection: `pm install-create -i`
         * wants to know who will show up as the installer of record, and the answer is "us".
         */
        @Provides
        @Singleton
        @IntoSet
        fun provideShizukuInstaller(
            shell: ShizukuShell,
            @ApplicationContext context: Context,
        ): Installer = ShellInstaller(shell, context.packageName)

        @Provides
        @Singleton
        @IntoSet
        fun provideRootInstaller(
            shell: RootShell,
            @ApplicationContext context: Context,
        ): Installer = ShellInstaller(shell, context.packageName)
    }
}
