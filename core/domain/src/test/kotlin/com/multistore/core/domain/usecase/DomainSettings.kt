package com.multistore.core.domain.usecase

import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.DownloadHistoryLimit
import com.multistore.core.model.ContentKind
import com.multistore.core.model.SearchSort
import kotlin.time.Duration
import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.InstallSettings
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.NetworkSettings
import com.multistore.core.model.RemoteConfigSettings
import com.multistore.core.model.DiagnosticsSettings
import com.multistore.core.model.NotificationSettings
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SecuritySettings
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.UpdateInterval
import com.multistore.core.model.UpdateSettings
import com.multistore.core.model.VersionSettings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The in-memory settings, for this module's tests.
 *
 * Local like `LocalSettings` in `:core:data`, and for the same reason: `FakeSettingsRepository` lives
 * in `:core:testing`, which depends on `:core:data` and cannot be imported from here without a cycle.
 *
 * One for the module, and not one per test class: `SyncIndexUseCaseTest` already had a copy of its
 * own, and the second would have made two places to update at the next `settings.proto` field. The
 * compiler would find both, but finding them is work all the same.
 */
internal class DomainSettings(
    metered: Boolean = false,
    blockUserAssistedChallenge: Boolean = false,
) : SettingsRepository {

    override val appearance = MutableStateFlow(AppearanceSettings())
    override val updates = MutableStateFlow(UpdateSettings())
    override val versions = MutableStateFlow(VersionSettings())
    override val installation = MutableStateFlow(InstallSettings())
    override val security = MutableStateFlow(SecuritySettings())
    override val remoteConfig = MutableStateFlow(RemoteConfigSettings())
    override val search = MutableStateFlow(SearchSettings())
    override val notifications = MutableStateFlow(NotificationSettings())
    override val diagnostics = MutableStateFlow(DiagnosticsSettings())
    override val storage = MutableStateFlow(StorageSettings())
    override val network = MutableStateFlow(
        NetworkSettings(
            meteredNetworkAllowed = metered,
            blockUserAssistedChallenge = blockUserAssistedChallenge,
        ),
    )

    override suspend fun setAllowPreviewChannels(allow: Boolean) = Unit
    override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
    override suspend fun setDynamicColor(enabled: Boolean) = Unit
    override suspend fun setLanguageTag(tag: String) = Unit
    override suspend fun setUpdateInterval(interval: UpdateInterval) = Unit
    override suspend fun setUpdateOnlyWhenCharging(only: Boolean) = Unit
    override suspend fun setAutoDownloadUpdates(auto: Boolean) = Unit
    override suspend fun setAutoInstallUpdates(auto: Boolean) = Unit
    override suspend fun setMuteUpdateNotifications(mute: Boolean) = Unit
    override suspend fun setInstallerPreference(preference: InstallerPreference) = Unit
    override suspend fun setAllowUnverifiedHash(allow: Boolean) = Unit
    override suspend fun setAllowSignerMismatch(allow: Boolean) = Unit
    override suspend fun setBlockRemoteParsers(block: Boolean) = Unit
    override suspend fun setBlockRemoteIndex(block: Boolean) = Unit
    override suspend fun setBlockSelfUpdateCheck(block: Boolean) = Unit
    override suspend fun setShowNsfwContent(show: Boolean) = Unit
    override suspend fun setSearchTimeout(timeout: Duration) = Unit
    override suspend fun setDefaultSort(sort: SearchSort) = Unit
    override suspend fun setDefaultContentKind(kind: ContentKind?) = Unit
    override suspend fun setMuteDownloadNotifications(mute: Boolean) = Unit
    override suspend fun setMuteInstallNotifications(mute: Boolean) = Unit
    override suspend fun setMuteStoreAlerts(mute: Boolean) = Unit
    override suspend fun setDiagnosticsLogEnabled(enabled: Boolean) = Unit
    override suspend fun setMeteredNetworkAllowed(allowed: Boolean) {
        network.value = network.value.copy(meteredNetworkAllowed = allowed)
    }

    override suspend fun setChallengeStrategy(strategy: ChallengeStrategy) = Unit
    override suspend fun setBlockUserAssistedChallenge(block: Boolean) = Unit

    override suspend fun setAllowWebAds(allow: Boolean) = Unit

    override suspend fun setKeepApkAfterInstall(keep: Boolean) {
        storage.value = storage.value.copy(keepApkAfterInstall = keep)
    }

    override suspend fun setImageCacheMaxMb(megabytes: Int) {
        storage.value = storage.value.copy(imageCacheMaxBytes = StorageSettings.megabytes(megabytes))
    }

    override suspend fun setCatalogRetention(retention: CatalogRetention) {
        storage.value = storage.value.copy(catalogRetention = retention)
    }

    override suspend fun setDownloadHistoryLimit(limit: DownloadHistoryLimit) {
        storage.value = storage.value.copy(downloadHistoryLimit = limit)
    }

    override suspend fun setAutoInstallAfterDownload(auto: Boolean) {
        installation.value = installation.value.copy(autoInstallAfterDownload = auto)
    }

}
