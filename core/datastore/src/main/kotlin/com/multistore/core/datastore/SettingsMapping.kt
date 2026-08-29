package com.multistore.core.datastore

import com.multistore.core.datastore.proto.Settings
import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.ContentKind
import com.multistore.core.model.DownloadHistoryLimit
import com.multistore.core.model.DiagnosticsSettings
import com.multistore.core.model.InstallSettings
import com.multistore.core.model.NetworkSettings
import com.multistore.core.model.NotificationSettings
import com.multistore.core.model.RemoteConfigSettings
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SearchSort
import com.multistore.core.model.SecuritySettings
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.SupportedLanguage
import com.multistore.core.model.UpdateSettings
import com.multistore.core.model.VersionSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.multistore.core.datastore.proto.CatalogRetention as ProtoCatalogRetention
import com.multistore.core.datastore.proto.ChallengeStrategy as ProtoChallengeStrategy
import com.multistore.core.datastore.proto.ContentKindFilter as ProtoContentKindFilter
import com.multistore.core.datastore.proto.DownloadHistoryLimit as ProtoDownloadHistoryLimit
import com.multistore.core.datastore.proto.InstallerPreference as ProtoInstallerPreference
import com.multistore.core.datastore.proto.SearchSort as ProtoSearchSort
import com.multistore.core.datastore.proto.ThemeMode as ProtoThemeMode
import com.multistore.core.datastore.proto.UpdateInterval as ProtoUpdateInterval
import com.multistore.core.model.ChallengeStrategy as DomainChallengeStrategy
import com.multistore.core.model.InstallerPreference as DomainInstallerPreference
import com.multistore.core.model.ThemeMode as DomainThemeMode
import com.multistore.core.model.UpdateInterval as DomainUpdateInterval

/**
 * Translation between the proto-generated enum and `:core:model`'s.
 *
 * Two enums exist on purpose: the design system and the features must not depend on the
 * persistence schema, or a change of storage would propagate all the way to the UI.
 */
internal fun ProtoThemeMode.toDomain(): DomainThemeMode = when (this) {
    ProtoThemeMode.THEME_MODE_LIGHT -> DomainThemeMode.LIGHT
    ProtoThemeMode.THEME_MODE_DARK -> DomainThemeMode.DARK
    // THEME_MODE_SYSTEM and UNRECOGNIZED (a value written by a future version) both fall back
    // to the default: that is the safe behaviour.
    else -> DomainThemeMode.SYSTEM
}

internal fun DomainThemeMode.toProto(): ProtoThemeMode = when (this) {
    DomainThemeMode.SYSTEM -> ProtoThemeMode.THEME_MODE_SYSTEM
    DomainThemeMode.LIGHT -> ProtoThemeMode.THEME_MODE_LIGHT
    DomainThemeMode.DARK -> ProtoThemeMode.THEME_MODE_DARK
}

/** `null` when the tag is empty or unrecognised: it means "follow the system". */
internal fun String.toSupportedLanguageOrNull(): SupportedLanguage? =
    takeIf { it.isNotBlank() }?.let(SupportedLanguage::fromTagOrNull)

internal fun Settings.toAppearance(): AppearanceSettings = AppearanceSettings(
    themeMode = themeMode.toDomain(),
    dynamicColor = dynamicColor,
    languageTag = languageTag,
)

internal fun ProtoUpdateInterval.toDomain(): DomainUpdateInterval = when (this) {
    ProtoUpdateInterval.UPDATE_INTERVAL_MANUAL -> DomainUpdateInterval.MANUAL
    ProtoUpdateInterval.UPDATE_INTERVAL_6_HOURS -> DomainUpdateInterval.EVERY_6_HOURS
    ProtoUpdateInterval.UPDATE_INTERVAL_12_HOURS -> DomainUpdateInterval.EVERY_12_HOURS
    ProtoUpdateInterval.UPDATE_INTERVAL_WEEKLY -> DomainUpdateInterval.WEEKLY
    // UPDATE_INTERVAL_DAILY and UNRECOGNIZED — a value written by a future version — both fall
    // back to the default, which here is also the behaviour that is wanted.
    else -> DomainUpdateInterval.DAILY
}

internal fun DomainUpdateInterval.toProto(): ProtoUpdateInterval = when (this) {
    DomainUpdateInterval.DAILY -> ProtoUpdateInterval.UPDATE_INTERVAL_DAILY
    DomainUpdateInterval.MANUAL -> ProtoUpdateInterval.UPDATE_INTERVAL_MANUAL
    DomainUpdateInterval.EVERY_6_HOURS -> ProtoUpdateInterval.UPDATE_INTERVAL_6_HOURS
    DomainUpdateInterval.EVERY_12_HOURS -> ProtoUpdateInterval.UPDATE_INTERVAL_12_HOURS
    DomainUpdateInterval.WEEKLY -> ProtoUpdateInterval.UPDATE_INTERVAL_WEEKLY
}

internal fun Settings.toUpdates(): UpdateSettings = UpdateSettings(
    interval = updateInterval.toDomain(),
    onlyWhenCharging = updateOnlyWhenCharging,
    autoDownload = autoDownloadUpdates,
    autoInstall = autoInstallUpdates,
    muteNotifications = muteUpdateNotifications,
)

internal fun Settings.toVersions(): VersionSettings = VersionSettings(
    allowPreviewChannels = allowPreviewChannels,
)

internal fun ProtoInstallerPreference.toDomain(): DomainInstallerPreference = when (this) {
    ProtoInstallerPreference.INSTALLER_PREFERENCE_SESSION -> DomainInstallerPreference.SESSION
    ProtoInstallerPreference.INSTALLER_PREFERENCE_SHIZUKU -> DomainInstallerPreference.SHIZUKU
    ProtoInstallerPreference.INSTALLER_PREFERENCE_ROOT -> DomainInstallerPreference.ROOT
    // INSTALLER_PREFERENCE_AUTOMATIC and UNRECOGNIZED — a value written by a future version,
    // for instance a fourth channel this build does not know — both fall back to the chain,
    // which is the behaviour that works everywhere.
    else -> DomainInstallerPreference.AUTOMATIC
}

internal fun DomainInstallerPreference.toProto(): ProtoInstallerPreference = when (this) {
    DomainInstallerPreference.AUTOMATIC -> ProtoInstallerPreference.INSTALLER_PREFERENCE_AUTOMATIC
    DomainInstallerPreference.SESSION -> ProtoInstallerPreference.INSTALLER_PREFERENCE_SESSION
    DomainInstallerPreference.SHIZUKU -> ProtoInstallerPreference.INSTALLER_PREFERENCE_SHIZUKU
    DomainInstallerPreference.ROOT -> ProtoInstallerPreference.INSTALLER_PREFERENCE_ROOT
}

internal fun Settings.toInstallation(): InstallSettings = InstallSettings(
    preference = installerPreference.toDomain(),
    autoInstallAfterDownload = autoInstallAfterDownload,
)

internal fun Settings.toSecurity(): SecuritySettings = SecuritySettings(
    allowUnverifiedHash = allowUnverifiedHash,
    allowSignerMismatch = allowSignerMismatch,
)

internal fun Settings.toRemoteConfig(): RemoteConfigSettings = RemoteConfigSettings(
    blockRemoteParsers = blockRemoteParsers,
    blockRemoteIndex = blockRemoteIndex,
    blockSelfUpdateCheck = blockSelfUpdateCheck,
)

internal fun Settings.toNotifications(): NotificationSettings = NotificationSettings(
    muteDownloadComplete = muteDownloadNotifications,
    muteInstallResult = muteInstallNotifications,
    muteStoreAlerts = muteStoreAlerts,
)

internal fun Settings.toDiagnostics(): DiagnosticsSettings = DiagnosticsSettings(
    logRequests = diagnosticsLogEnabled,
)

internal fun Settings.toSearch(): SearchSettings = SearchSettings(
    showNsfwContent = showNsfwContent,
    storeTimeout = searchTimeoutSeconds.toStoreTimeout(),
    defaultSort = defaultSort.toDomain(),
    defaultContentKind = defaultContentKind.toDomain(),
)

internal fun ProtoSearchSort.toDomain(): SearchSort = when (this) {
    ProtoSearchSort.SEARCH_SORT_NAME -> SearchSort.NAME
    ProtoSearchSort.SEARCH_SORT_RATING -> SearchSort.RATING
    // SEARCH_SORT_RELEVANCE and UNRECOGNIZED — a value written by a future version — both fall
    // back to the aggregator's order, which is also the only one a build that does not know that
    // value could produce.
    else -> SearchSort.RELEVANCE
}

internal fun SearchSort.toProto(): ProtoSearchSort = when (this) {
    SearchSort.NAME -> ProtoSearchSort.SEARCH_SORT_NAME
    SearchSort.RATING -> ProtoSearchSort.SEARCH_SORT_RATING
    // The three criteria the aggregated search cannot compute — see `SearchSort.SELECTABLE` —
    // have no proto value, and that is not an oversight: writing a field the reader cannot
    // interpret would give a preference that is saved and never applied.
    else -> ProtoSearchSort.SEARCH_SORT_RELEVANCE
}

internal fun ProtoContentKindFilter.toDomain(): ContentKind? = when (this) {
    ProtoContentKindFilter.CONTENT_KIND_FILTER_APPS -> ContentKind.APP
    ProtoContentKindFilter.CONTENT_KIND_FILTER_GAMES -> ContentKind.GAME
    // CONTENT_KIND_FILTER_ALL and UNRECOGNIZED: no filter. It is also the only safe fallback —
    // an unknown value read as "only something" would hide half the catalogue and exclude eight
    // stores out of nine from the search.
    else -> null
}

internal fun ContentKind?.toProto(): ProtoContentKindFilter = when (this) {
    ContentKind.APP -> ProtoContentKindFilter.CONTENT_KIND_FILTER_APPS
    ContentKind.GAME -> ProtoContentKindFilter.CONTENT_KIND_FILTER_GAMES
    // `UNKNOWN` is not a user choice but a list row's answer: "the store does not say". A
    // filter reading "show only what we do not know the kind of" means nothing, so it counts as
    // no filter.
    else -> ProtoContentKindFilter.CONTENT_KIND_FILTER_ALL
}

/**
 * The per-store timeout, with zero meaning "never written".
 *
 * This project's zero-value rule applied to a **number** for the first time, and the difference
 * from `bool`s and enums is that here the trap is not visible when choosing a name: `0` is a
 * legitimate domain value. Taken literally it would say "wait for no store", i.e. zero results
 * from all nine on first launch — with no message connecting the two facts.
 *
 * ### One check, and why not two
 *
 * The first draft had an explicit `if (this <= 0)` **before** the range comparison. Injecting the
 * fault — changing it to `< 0`, letting zero through — failed nothing, and rightly so:
 * `0.seconds` is not in `2s..60s`, so it fell back to the default anyway. That branch was not a
 * defence, it was a caption shaped like an `if`.
 *
 * One comparison therefore remains, and the link that makes it sufficient is written where it
 * needs reading: the minimum of [SearchSettings.STORE_TIMEOUT_RANGE] **must stay above zero**,
 * because that is also what makes "never written" an out-of-range value.
 *
 * Too large a value falls back to the default rather than being clamped: clamping 100000 to 60
 * would give a minute's wait nobody asked for.
 */
internal fun Int.toStoreTimeout(): Duration {
    val declared = seconds
    return if (declared in SearchSettings.STORE_TIMEOUT_RANGE) {
        declared
    } else {
        SearchSettings.DEFAULT_STORE_TIMEOUT
    }
}

internal fun ProtoChallengeStrategy.toDomain(): DomainChallengeStrategy = when (this) {
    ProtoChallengeStrategy.CHALLENGE_STRATEGY_CONSERVATIVE -> DomainChallengeStrategy.CONSERVATIVE
    ProtoChallengeStrategy.CHALLENGE_STRATEGY_AGGRESSIVE -> DomainChallengeStrategy.AGGRESSIVE
    // CHALLENGE_STRATEGY_BALANCED and UNRECOGNIZED — a fourth value written by a future
    // version — both fall back to the default. Here the fallback is not merely prudent but the
    // only useful one: a build that does not know that value would not know what to do with it,
    // and dropping to "network only" would silently take a store away from the user.
    else -> DomainChallengeStrategy.BALANCED
}

internal fun DomainChallengeStrategy.toProto(): ProtoChallengeStrategy = when (this) {
    DomainChallengeStrategy.BALANCED -> ProtoChallengeStrategy.CHALLENGE_STRATEGY_BALANCED
    DomainChallengeStrategy.CONSERVATIVE -> ProtoChallengeStrategy.CHALLENGE_STRATEGY_CONSERVATIVE
    DomainChallengeStrategy.AGGRESSIVE -> ProtoChallengeStrategy.CHALLENGE_STRATEGY_AGGRESSIVE
}

internal fun Settings.toNetwork(): NetworkSettings = NetworkSettings(
    meteredNetworkAllowed = meteredNetworkAllowed,
    challengeStrategy = challengeStrategy.toDomain(),
    blockUserAssistedChallenge = blockUserAssistedChallenge,
    allowWebAds = allowWebAds,
)

internal fun Settings.toStorage(): StorageSettings = StorageSettings(
    keepApkAfterInstall = keepApkAfterInstall,
    imageCacheMaxBytes = imageCacheMaxMb.toImageCacheBytes(),
    catalogRetention = catalogRetention.toDomain(),
    downloadHistoryLimit = downloadHistoryLimit.toDomain(),
)

/**
 * The image cache ceiling, with zero meaning "never written".
 *
 * The **second** number in this file after [Int.toStoreTimeout], and the difference between the
 * two is why this function exists instead of a `coerceIn`.
 *
 * On the timeout, zero is an **absurd** value: "wait for no store" would produce a search that
 * never returns anything, a defect visible on the first try. Here zero would be **plausible** —
 * "keep no icons on disk" is something someone might ask for — and an app re-downloading every
 * icon on every scroll does not look broken, it looks slow. A plausible zero is the more
 * dangerous of the two, because nobody questions it: that is why the minimum of
 * [StorageSettings.IMAGE_CACHE_MB_RANGE] must stay above zero.
 *
 * Out of range falls back to the default rather than clamping, as with the timeout: a future
 * version writing 100000 must not leave four gigabytes of icons on someone who did not ask.
 */
internal fun Int.toImageCacheBytes(): Long =
    if (this in StorageSettings.IMAGE_CACHE_MB_RANGE) {
        StorageSettings.megabytes(this)
    } else {
        StorageSettings.DEFAULT_IMAGE_CACHE_BYTES
    }

internal fun ProtoCatalogRetention.toDomain(): CatalogRetention = when (this) {
    ProtoCatalogRetention.CATALOG_RETENTION_7_DAYS -> CatalogRetention.SEVEN_DAYS
    ProtoCatalogRetention.CATALOG_RETENTION_90_DAYS -> CatalogRetention.NINETY_DAYS
    ProtoCatalogRetention.CATALOG_RETENTION_KEEP -> CatalogRetention.KEEP
    // CATALOG_RETENTION_30_DAYS and UNRECOGNIZED — a value written by a future version — both
    // fall back to the default. The prudent fallback here is the one that does **not** delete
    // more than the user asked for.
    else -> CatalogRetention.THIRTY_DAYS
}

internal fun CatalogRetention.toProto(): ProtoCatalogRetention = when (this) {
    CatalogRetention.SEVEN_DAYS -> ProtoCatalogRetention.CATALOG_RETENTION_7_DAYS
    CatalogRetention.THIRTY_DAYS -> ProtoCatalogRetention.CATALOG_RETENTION_30_DAYS
    CatalogRetention.NINETY_DAYS -> ProtoCatalogRetention.CATALOG_RETENTION_90_DAYS
    CatalogRetention.KEEP -> ProtoCatalogRetention.CATALOG_RETENTION_KEEP
}

internal fun ProtoDownloadHistoryLimit.toDomain(): DownloadHistoryLimit = when (this) {
    ProtoDownloadHistoryLimit.DOWNLOAD_HISTORY_LIMIT_50 -> DownloadHistoryLimit.LAST_50
    ProtoDownloadHistoryLimit.DOWNLOAD_HISTORY_LIMIT_500 -> DownloadHistoryLimit.LAST_500
    ProtoDownloadHistoryLimit.DOWNLOAD_HISTORY_LIMIT_ALL -> DownloadHistoryLimit.KEEP_ALL
    // DOWNLOAD_HISTORY_LIMIT_100 and UNRECOGNIZED — a value written by a future version — both
    // fall back to the default. The prudent fallback is again the one that does **not** delete
    // more than the user asked for: a build that does not know the value must not read it as
    // "keep fewer".
    else -> DownloadHistoryLimit.LAST_100
}

internal fun DownloadHistoryLimit.toProto(): ProtoDownloadHistoryLimit = when (this) {
    DownloadHistoryLimit.LAST_100 -> ProtoDownloadHistoryLimit.DOWNLOAD_HISTORY_LIMIT_100
    DownloadHistoryLimit.LAST_50 -> ProtoDownloadHistoryLimit.DOWNLOAD_HISTORY_LIMIT_50
    DownloadHistoryLimit.LAST_500 -> ProtoDownloadHistoryLimit.DOWNLOAD_HISTORY_LIMIT_500
    DownloadHistoryLimit.KEEP_ALL -> ProtoDownloadHistoryLimit.DOWNLOAD_HISTORY_LIMIT_ALL
}
