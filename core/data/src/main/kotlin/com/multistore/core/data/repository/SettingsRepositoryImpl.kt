package com.multistore.core.data.repository

import com.multistore.core.datastore.SettingsLocalDataSource
import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.DownloadHistoryLimit
import com.multistore.core.model.ContentKind
import com.multistore.core.model.DiagnosticsSettings
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.InstallSettings
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.NetworkSettings
import com.multistore.core.model.NotificationSettings
import com.multistore.core.model.RemoteConfigSettings
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SearchSort
import com.multistore.core.model.SecuritySettings
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.UpdateInterval
import com.multistore.core.model.UpdateSettings
import com.multistore.core.model.VersionSettings
import kotlin.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
internal class SettingsRepositoryImpl @Inject constructor(
    private val local: SettingsLocalDataSource,
) : SettingsRepository {

    override val appearance: Flow<AppearanceSettings> = local.appearance

    override val updates: Flow<UpdateSettings> = local.updates

    override val versions: Flow<VersionSettings> = local.versions

    override val installation: Flow<InstallSettings> = local.installation

    override val security: Flow<SecuritySettings> = local.security

    override val remoteConfig: Flow<RemoteConfigSettings> = local.remoteConfig

    override val search: Flow<SearchSettings> = local.search

    override val notifications: Flow<NotificationSettings> = local.notifications

    override val diagnostics: Flow<DiagnosticsSettings> = local.diagnostics

    override val network: Flow<NetworkSettings> = local.network

    override val storage: Flow<StorageSettings> = local.storage

    override suspend fun setThemeMode(themeMode: ThemeMode) = local.setThemeMode(themeMode)

    override suspend fun setDynamicColor(enabled: Boolean) = local.setDynamicColor(enabled)

    override suspend fun setLanguageTag(tag: String) = local.setLanguageTag(tag)

    override suspend fun setUpdateInterval(interval: UpdateInterval) =
        local.setUpdateInterval(interval)

    override suspend fun setUpdateOnlyWhenCharging(only: Boolean) =
        local.setUpdateOnlyWhenCharging(only)

    override suspend fun setAutoDownloadUpdates(auto: Boolean) = local.setAutoDownloadUpdates(auto)

    override suspend fun setAutoInstallUpdates(auto: Boolean) = local.setAutoInstallUpdates(auto)

    override suspend fun setMuteUpdateNotifications(mute: Boolean) =
        local.setMuteUpdateNotifications(mute)

    override suspend fun setAllowPreviewChannels(allow: Boolean) =
        local.setAllowPreviewChannels(allow)

    override suspend fun setInstallerPreference(preference: InstallerPreference) =
        local.setInstallerPreference(preference)

    override suspend fun setAllowUnverifiedHash(allow: Boolean) = local.setAllowUnverifiedHash(allow)

    override suspend fun setAllowSignerMismatch(allow: Boolean) = local.setAllowSignerMismatch(allow)

    override suspend fun setBlockRemoteParsers(block: Boolean) = local.setBlockRemoteParsers(block)

    override suspend fun setBlockRemoteIndex(block: Boolean) = local.setBlockRemoteIndex(block)

    override suspend fun setBlockSelfUpdateCheck(block: Boolean) = local.setBlockSelfUpdateCheck(block)

    override suspend fun setShowNsfwContent(show: Boolean) = local.setShowNsfwContent(show)

    override suspend fun setSearchTimeout(timeout: Duration) = local.setSearchTimeout(timeout)

    override suspend fun setDefaultSort(sort: SearchSort) = local.setDefaultSort(sort)

    override suspend fun setDefaultContentKind(kind: ContentKind?) = local.setDefaultContentKind(kind)

    override suspend fun setMuteDownloadNotifications(mute: Boolean) =
        local.setMuteDownloadNotifications(mute)

    override suspend fun setMuteInstallNotifications(mute: Boolean) =
        local.setMuteInstallNotifications(mute)

    override suspend fun setMuteStoreAlerts(mute: Boolean) = local.setMuteStoreAlerts(mute)

    override suspend fun setDiagnosticsLogEnabled(enabled: Boolean) =
        local.setDiagnosticsLogEnabled(enabled)

    override suspend fun setMeteredNetworkAllowed(allowed: Boolean) =
        local.setMeteredNetworkAllowed(allowed)

    override suspend fun setChallengeStrategy(strategy: ChallengeStrategy) =
        local.setChallengeStrategy(strategy)

    override suspend fun setBlockUserAssistedChallenge(block: Boolean) =
        local.setBlockUserAssistedChallenge(block)

    override suspend fun setAllowWebAds(allow: Boolean) = local.setAllowWebAds(allow)

    override suspend fun setKeepApkAfterInstall(keep: Boolean) = local.setKeepApkAfterInstall(keep)

    override suspend fun setImageCacheMaxMb(megabytes: Int) = local.setImageCacheMaxMb(megabytes)

    override suspend fun setCatalogRetention(retention: CatalogRetention) =
        local.setCatalogRetention(retention)

    override suspend fun setDownloadHistoryLimit(limit: DownloadHistoryLimit) =
        local.setDownloadHistoryLimit(limit)

    override suspend fun setAutoInstallAfterDownload(auto: Boolean) =
        local.setAutoInstallAfterDownload(auto)
}
