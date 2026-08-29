package com.multistore.core.datastore

import androidx.datastore.core.DataStore
import com.multistore.core.datastore.proto.Settings
import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.ContentKind
import com.multistore.core.model.DownloadHistoryLimit
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
import java.io.IOException
import kotlin.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/**
 * The only place in the project that touches the types generated from `settings.proto`.
 *
 * Everything above it speaks `:core:model`. The benefit is not theoretical: adding a field to the
 * proto, or changing the storage, has no effect beyond this file.
 */
@Singleton
class SettingsLocalDataSource @Inject constructor(
    private val dataStore: DataStore<Settings>,
    private val serializer: SettingsSerializer,
) {

    /**
     * The raw flow, with error handling applied **once**.
     *
     * There is a trap worth naming: `Flow.catch` **terminates** the stream after emitting. Used
     * alone it would mean that on the first I/O error — a momentarily full disk, a locked file —
     * the flow closes and settings stop updating *for the life of the process*: the user flips a
     * switch and nothing happens again until the app restarts.
     *
     * Hence `retryWhen`, which resubscribes to `dataStore.data` instead of closing it, and only
     * falls back to the defaults after several failed attempts. And the defaults are the
     * serializer's, not a `getDefaultInstance()` built here: one place where they exist.
     */
    private val settings: Flow<Settings> = dataStore.data
        .retryWhen { cause, attempt ->
            val retriable = cause is IOException && attempt < MAX_IO_RETRIES
            if (retriable) delay(IO_RETRY_DELAY_MS * (attempt + 1))
            retriable
        }
        .catch { throwable ->
            if (throwable is IOException) emit(serializer.defaultValue) else throw throwable
        }

    /**
     * The appearance settings.
     *
     * `distinctUntilChanged` on each projection, not on the raw flow: `Settings` changes every
     * time any field is touched, and without this filter changing the language would recompose
     * whoever observes only security.
     */
    val appearance: Flow<AppearanceSettings> = settings
        .map { it.toAppearance() }
        .distinctUntilChanged()

    val updates: Flow<UpdateSettings> = settings
        .map { it.toUpdates() }
        .distinctUntilChanged()

    val versions: Flow<VersionSettings> = settings
        .map { it.toVersions() }
        .distinctUntilChanged()

    val installation: Flow<InstallSettings> = settings
        .map { it.toInstallation() }
        .distinctUntilChanged()

    val security: Flow<SecuritySettings> = settings
        .map { it.toSecurity() }
        .distinctUntilChanged()

    val remoteConfig: Flow<RemoteConfigSettings> = settings
        .map { it.toRemoteConfig() }
        .distinctUntilChanged()

    val search: Flow<SearchSettings> = settings
        .map { it.toSearch() }
        .distinctUntilChanged()

    val network: Flow<NetworkSettings> = settings
        .map { it.toNetwork() }
        .distinctUntilChanged()

    val notifications: Flow<NotificationSettings> = settings
        .map { it.toNotifications() }
        .distinctUntilChanged()

    val diagnostics: Flow<DiagnosticsSettings> = settings
        .map { it.toDiagnostics() }
        .distinctUntilChanged()

    val storage: Flow<StorageSettings> = settings
        .map { it.toStorage() }
        .distinctUntilChanged()

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.updateData { it.toBuilder().setThemeMode(themeMode.toProto()).build() }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.updateData { it.toBuilder().setDynamicColor(enabled).build() }
    }

    /** Empty tag = go back to following the system language. */
    suspend fun setLanguageTag(tag: String) {
        dataStore.updateData { it.toBuilder().setLanguageTag(tag).build() }
    }

    suspend fun setUpdateInterval(interval: UpdateInterval) {
        dataStore.updateData { it.toBuilder().setUpdateInterval(interval.toProto()).build() }
    }

    suspend fun setUpdateOnlyWhenCharging(only: Boolean) {
        dataStore.updateData { it.toBuilder().setUpdateOnlyWhenCharging(only).build() }
    }

    suspend fun setAutoDownloadUpdates(auto: Boolean) {
        dataStore.updateData { it.toBuilder().setAutoDownloadUpdates(auto).build() }
    }

    suspend fun setAutoInstallUpdates(auto: Boolean) {
        dataStore.updateData { it.toBuilder().setAutoInstallUpdates(auto).build() }
    }

    suspend fun setAllowPreviewChannels(allow: Boolean) {
        dataStore.updateData { it.toBuilder().setAllowPreviewChannels(allow).build() }
    }

    suspend fun setInstallerPreference(preference: InstallerPreference) {
        dataStore.updateData { it.toBuilder().setInstallerPreference(preference.toProto()).build() }
    }

    suspend fun setAutoInstallAfterDownload(auto: Boolean) {
        dataStore.updateData { it.toBuilder().setAutoInstallAfterDownload(auto).build() }
    }

    suspend fun setDownloadHistoryLimit(limit: DownloadHistoryLimit) {
        dataStore.updateData { it.toBuilder().setDownloadHistoryLimit(limit.toProto()).build() }
    }

    suspend fun setMuteUpdateNotifications(mute: Boolean) {
        dataStore.updateData { it.toBuilder().setMuteUpdateNotifications(mute).build() }
    }

    suspend fun setAllowUnverifiedHash(allow: Boolean) {
        dataStore.updateData { it.toBuilder().setAllowUnverifiedHash(allow).build() }
    }

    suspend fun setAllowSignerMismatch(allow: Boolean) {
        dataStore.updateData { it.toBuilder().setAllowSignerMismatch(allow).build() }
    }

    suspend fun setBlockRemoteParsers(block: Boolean) {
        dataStore.updateData { it.toBuilder().setBlockRemoteParsers(block).build() }
    }

    suspend fun setBlockRemoteIndex(block: Boolean) {
        dataStore.updateData { it.toBuilder().setBlockRemoteIndex(block).build() }
    }

    suspend fun setBlockSelfUpdateCheck(block: Boolean) {
        dataStore.updateData { it.toBuilder().setBlockSelfUpdateCheck(block).build() }
    }

    suspend fun setShowNsfwContent(show: Boolean) {
        dataStore.updateData { it.toBuilder().setShowNsfwContent(show).build() }
    }

    /**
     * Writes the per-store timeout, in whole seconds.
     *
     * Rounding down does not matter: the values the screen offers are all whole seconds. What
     * matters is that zero is never written here — that value is reserved for "never written",
     * and writing it deliberately would ask the translation to replace it with the default.
     */
    suspend fun setSearchTimeout(timeout: Duration) {
        val seconds = timeout.inWholeSeconds.toInt().coerceAtLeast(1)
        dataStore.updateData { it.toBuilder().setSearchTimeoutSeconds(seconds).build() }
    }

    suspend fun setDefaultSort(sort: SearchSort) {
        dataStore.updateData { it.toBuilder().setDefaultSort(sort.toProto()).build() }
    }

    suspend fun setDefaultContentKind(kind: ContentKind?) {
        dataStore.updateData { it.toBuilder().setDefaultContentKind(kind.toProto()).build() }
    }

    suspend fun setMuteDownloadNotifications(mute: Boolean) {
        dataStore.updateData { it.toBuilder().setMuteDownloadNotifications(mute).build() }
    }

    suspend fun setMuteInstallNotifications(mute: Boolean) {
        dataStore.updateData { it.toBuilder().setMuteInstallNotifications(mute).build() }
    }

    suspend fun setMuteStoreAlerts(mute: Boolean) {
        dataStore.updateData { it.toBuilder().setMuteStoreAlerts(mute).build() }
    }

    suspend fun setDiagnosticsLogEnabled(enabled: Boolean) {
        dataStore.updateData { it.toBuilder().setDiagnosticsLogEnabled(enabled).build() }
    }

    suspend fun setMeteredNetworkAllowed(allowed: Boolean) {
        dataStore.updateData { it.toBuilder().setMeteredNetworkAllowed(allowed).build() }
    }

    suspend fun setChallengeStrategy(strategy: ChallengeStrategy) {
        dataStore.updateData { it.toBuilder().setChallengeStrategy(strategy.toProto()).build() }
    }

    suspend fun setKeepApkAfterInstall(keep: Boolean) {
        dataStore.updateData { it.toBuilder().setKeepApkAfterInstall(keep).build() }
    }

    /**
     * Writes the image cache ceiling, in whole megabytes.
     *
     * As with [setSearchTimeout], **zero is never written here**: that value is reserved for
     * "never written". The difference is that there writing it by mistake would give an empty
     * search — a defect visible on the first try — while here it would give a slightly slower
     * app, the kind of defect nobody connects to a setting.
     */
    suspend fun setImageCacheMaxMb(megabytes: Int) {
        val clamped = megabytes.coerceIn(StorageSettings.IMAGE_CACHE_MB_RANGE)
        dataStore.updateData { it.toBuilder().setImageCacheMaxMb(clamped).build() }
    }

    suspend fun setCatalogRetention(retention: CatalogRetention) {
        dataStore.updateData { it.toBuilder().setCatalogRetention(retention.toProto()).build() }
    }

    suspend fun setBlockUserAssistedChallenge(block: Boolean) {
        dataStore.updateData { it.toBuilder().setBlockUserAssistedChallenge(block).build() }
    }

    suspend fun setAllowWebAds(allow: Boolean) {
        dataStore.updateData { it.toBuilder().setAllowWebAds(allow).build() }
    }

    private companion object {
        // Three attempts are enough to ride out a transient lock; beyond that the problem is
        // not transient and insisting would only keep a coroutine busy forever.
        const val MAX_IO_RETRIES = 3L
        const val IO_RETRY_DELAY_MS = 100L
    }
}
