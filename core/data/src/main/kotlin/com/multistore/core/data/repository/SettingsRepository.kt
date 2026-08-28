package com.multistore.core.data.repository

import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.CatalogRetention
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
import kotlinx.coroutines.flow.Flow

/**
 * Access to the user settings.
 *
 * The sections exist when the fields filling them exist. The rule tying the two together: a field
 * with no entry in Settings makes `SettingsCoverageTest` fail, and vice versa.
 *
 * The flows are three and not one because the consumers are disjoint: the theme observes
 * [appearance], the verification pipeline observes [security], the index sync observes [network]. A
 * single flow would wake them all up at every touch of any switch.
 */
interface SettingsRepository {

    val appearance: Flow<AppearanceSettings>

    /**
     * How the update check behaves when it starts by itself.
     *
     * A flow of its own like the other three, and for the same reason: its observer is the worker's
     * scheduling, which has nothing to do when the user changes theme.
     */
    val updates: Flow<UpdateSettings>

    /**
     * Which installer to proceed with.
     *
     * A flow of its own and not a field of [security]: the verification pipeline does not change by a
     * line depending on the channel, and whoever observes this — `InstallRepositoryImpl` when
     * choosing — has nothing to do when a security setting changes.
     */
    /**
     * Which versions the app can choose by itself.
     *
     * A flow of its own because its readers are two and are only its own: the two repositories
     * calling `selectVersion` — the detail screen and the update check. It is read by the repository,
     * **not** by the caller: the same note as [search] applies here, because the same reason applies —
     * a setting passed as a parameter is a setting somebody eventually forgets to pass, and forgetting
     * produces no error.
     */
    val versions: Flow<VersionSettings>

    val installation: Flow<InstallSettings>

    val security: Flow<SecuritySettings>

    /**
     * What the app agrees to receive from us.
     *
     * It is read by the configuration update at startup, which runs once and observes nothing: it is
     * a flow because the Settings screen has to see the value change, not because anyone stays
     * listening.
     */
    val remoteConfig: Flow<RemoteConfigSettings>

    /**
     * What the search can show.
     *
     * It is read by `SearchRepository`, **not** by the search's caller. See the note on
     * `SearchRepositoryImpl.effectiveFilters`: a setting of this kind passed as a parameter would be
     * a setting somebody eventually forgets to pass.
     */
    val search: Flow<SearchSettings>

    val notifications: Flow<NotificationSettings>

    val diagnostics: Flow<DiagnosticsSettings>

    val network: Flow<NetworkSettings>

    /** How much space the app can occupy, and how long to keep an expired listing. */
    val storage: Flow<StorageSettings>

    suspend fun setThemeMode(themeMode: ThemeMode)

    suspend fun setDynamicColor(enabled: Boolean)

    /** A BCP-47 tag; an empty string goes back to following the system language. */
    suspend fun setLanguageTag(tag: String)

    suspend fun setUpdateInterval(interval: UpdateInterval)

    suspend fun setUpdateOnlyWhenCharging(only: Boolean)

    suspend fun setAutoDownloadUpdates(auto: Boolean)

    suspend fun setAutoInstallUpdates(auto: Boolean)

    suspend fun setMuteUpdateNotifications(mute: Boolean)

    suspend fun setAllowPreviewChannels(allow: Boolean)

    suspend fun setInstallerPreference(preference: InstallerPreference)

    suspend fun setAllowUnverifiedHash(allow: Boolean)

    suspend fun setAllowSignerMismatch(allow: Boolean)

    suspend fun setBlockRemoteParsers(block: Boolean)

    suspend fun setBlockRemoteIndex(block: Boolean)

    suspend fun setBlockSelfUpdateCheck(block: Boolean)

    suspend fun setShowNsfwContent(show: Boolean)

    suspend fun setSearchTimeout(timeout: Duration)

    suspend fun setDefaultSort(sort: SearchSort)

    suspend fun setDefaultContentKind(kind: ContentKind?)

    suspend fun setMuteDownloadNotifications(mute: Boolean)

    suspend fun setMuteInstallNotifications(mute: Boolean)

    suspend fun setMuteStoreAlerts(mute: Boolean)

    suspend fun setDiagnosticsLogEnabled(enabled: Boolean)

    suspend fun setMeteredNetworkAllowed(allowed: Boolean)

    suspend fun setChallengeStrategy(strategy: ChallengeStrategy)

    suspend fun setBlockUserAssistedChallenge(block: Boolean)

    /** Lets advertising through in the assisted download's WebView. */
    suspend fun setAllowWebAds(allow: Boolean)

    suspend fun setKeepApkAfterInstall(keep: Boolean)

    suspend fun setImageCacheMaxMb(megabytes: Int)

    suspend fun setCatalogRetention(retention: CatalogRetention)
}
