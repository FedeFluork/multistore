package com.multistore.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.ContentKind
import com.multistore.core.model.SearchSort
import dagger.hilt.android.lifecycle.HiltViewModel
import com.multistore.core.data.repository.DiagnosticsRepository
import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.MaintenanceRepository
import com.multistore.core.data.repository.RemoteConfigRepository
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.data.repository.StoreHealthRepository
import com.multistore.core.model.StorageLevel
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.StorageUsage
import com.multistore.core.model.StoreId
import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.DiagnosticsSettings
import com.multistore.core.model.DownloadHistoryLimit
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.InstallSettings
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.NetworkSettings
import com.multistore.core.model.NotificationSettings
import com.multistore.core.model.RemoteConfigSettings
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SecuritySettings
import com.multistore.core.model.SupportedLanguage
import com.multistore.core.remoteconfig.RemoteConfigStatus
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.UpdateInterval
import com.multistore.core.model.UpdateSettings
import com.multistore.core.model.VersionSettings
import java.io.IOException
import kotlin.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Settings screen's state. */
sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    data class Ready(
        val appearance: AppearanceSettings,
        val updates: UpdateSettings,
        val versions: VersionSettings,
        val installation: InstallSettings,
        val security: SecuritySettings,
        val network: NetworkSettings,
        val remoteConfig: RemoteConfigSettings,
        val search: SearchSettings,
        val notifications: NotificationSettings,
        val diagnostics: DiagnosticsSettings,
    ) : SettingsUiState
}

/**
 * The outcome of an export, which is an action and therefore has a before, a during and an after.
 *
 * `Failed` exists and is not defensive laziness: whoever exports chooses **where**, and the chosen
 * folder can be full, unmounted, or on a provider that refuses the write. Staying on [Preparing]
 * forever would be the worst possible answer.
 */
sealed interface ExportUiState {
    data object Idle : ExportUiState
    data object Preparing : ExportUiState
    data object Done : ExportUiState
    data object Failed : ExportUiState
}

/**
 * The Storage section, in a single value.
 *
 * It holds together things of different natures — two and a half settings from the DataStore, four
 * sizes read from disk, and which clear is running — and that is not laziness: a level's row shows
 * **all three together**, and keeping them in three separate flows would mean three staggered
 * recompositions for one button press.
 */
data class StorageUiState(
    val settings: StorageSettings = StorageSettings(),
    val usage: StorageUsage = StorageUsage.UNKNOWN,
    /** Which level is being cleared right now, if any. */
    val busy: StorageLevel? = null,
    /**
     * How much the last clear of each level freed.
     *
     * A map rather than a single outcome: the four buttons are in a column and get pressed one after
     * another, and a single outcome would make the previous result vanish the instant the next is
     * pressed — that is, exactly while the user is comparing the numbers.
     */
    val freed: Map<StorageLevel, Long> = emptyMap(),
)

/** The state of the "Reclaim space" button: an action, so a before, a during and an after. */
sealed interface ReclaimUiState {
    data object Idle : ReclaimUiState
    data object Running : ReclaimUiState

    /**
     * [freedBytes] at zero is a legitimate outcome and must be worded differently from a saving:
     * "there was nothing to reclaim" and "0 bytes freed" read very differently.
     */
    data class Done(val freedBytes: Long) : ReclaimUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val maintenance: MaintenanceRepository,
    private val storeHealth: StoreHealthRepository,
    private val installs: InstallRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val diagnostics: DiagnosticsRepository,
) : ViewModel() {

    /**
     * Which installers this device offers right now.
     *
     * A `StateFlow` re-read on demand rather than an observable flow, because there is nothing to
     * observe: Shizuku can start or stop at any moment and nobody tells us. It is re-read when the
     * screen opens and after every permission request — the two moments when the value may have just
     * changed at the user's hand.
     */
    private val _installers = MutableStateFlow(InstallerAvailability())
    val installers: StateFlow<InstallerAvailability> = _installers.asStateFlow()

    init {
        refreshInstallers()
    }

    fun refreshInstallers() {
        viewModelScope.launch { _installers.value = installs.installerAvailability() }
    }

    /**
     * Picks the installation method, and — if needed — asks for it.
     *
     * The preference is written **regardless**, even when the channel is unusable: that is
     * `InstallerSelector`'s promise, preferring is not requiring. Whoever picks Shizuku before
     * starting it must not have to come back here afterwards.
     *
     * The permission, on the other hand, is asked for only if missing and only now: here the app is
     * in the foreground, and Shizuku's dialog would not appear anywhere else.
     */
    fun setInstallerPreference(preference: InstallerPreference) {
        update { settingsRepository.setInstallerPreference(preference) }

        val kind = preference.kind ?: return
        if (kind in _installers.value.usable) return
        viewModelScope.launch {
            val granted = runCatching { installs.requestInstallerPermission(kind) }.getOrDefault(false)
            _installers.value = installs.installerAvailability()
            if (!granted) _permissionDenials.tryEmit(kind)
        }
    }

    /** A channel chosen and not granted: the screen says so instead of leaving it to be guessed. */
    private val _permissionDenials = MutableSharedFlow<InstallerKind>(extraBufferCapacity = 1)
    val permissionDenials: SharedFlow<InstallerKind> = _permissionDenials.asSharedFlow()

    /**
     * The stores, with the user's choice and the breaker state.
     *
     * A separate flow from [uiState] rather than a field of it: it comes from Room, not the
     * DataStore, and changes for reasons of its own — a circuit breaker opening is not a setting the
     * user touched. Merging them would force the screen to redraw the preferences every time a store
     * answers badly.
     */
    val stores: StateFlow<List<StoreEntry>> = storeHealth.observeStores()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Turns a store on or off.
     *
     * Writes to Room and not to `settings.proto`, deliberately: the `enabled` column of the `stores`
     * table is already the one `SearchRepository` reads to decide who to query. A second copy in the
     * DataStore would be a value that can diverge from the one actually in charge.
     */
    fun setStoreEnabled(storeId: StoreId, enabled: Boolean) {
        viewModelScope.launch { runCatching { storeHealth.setEnabled(storeId, enabled) } }
    }

    /** The part of [StorageUiState] that does not come from the DataStore. */
    private data class StorageOps(
        val usage: StorageUsage = StorageUsage.UNKNOWN,
        val busy: StorageLevel? = null,
        val freed: Map<StorageLevel, Long> = emptyMap(),
    )

    private val _storageOps = MutableStateFlow(StorageOps())

    val storage: StateFlow<StorageUiState> =
        combine(settingsRepository.storage, _storageOps) { settings, ops ->
            StorageUiState(settings = settings, usage = ops.usage, busy = ops.busy, freed = ops.freed)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StorageUiState(),
        )

    /**
     * Re-measures the four levels.
     *
     * On demand rather than observed: none of the four folders notifies when it changes, and
     * "observing" them would mean walking a file tree on a timer for a number the user looks at for
     * two seconds. The moments when it can have changed for a reason they know of are two: when they
     * open the screen, and after they have pressed something.
     */
    fun refreshStorageUsage() {
        viewModelScope.launch {
            val usage = runCatching { maintenance.usage() }.getOrNull() ?: return@launch
            _storageOps.value = _storageOps.value.copy(usage = usage)
        }
    }

    /**
     * The first measurement, and it is **here** rather than in the `init` at the top.
     *
     * `init` blocks run in the order they are written, interleaved with the property initialisers:
     * calling this function from the block at the head of the class leaves `_storageOps` not yet
     * created, and the coroutine dies with a `NullPointerException` on a `val` field. The dispatcher
     * swallows it, so it is invisible in production — it showed up because nine ViewModel tests went
     * red together.
     */
    init {
        refreshStorageUsage()
    }

    /**
     * Clears one level.
     *
     * Re-measures **all four** at the end and not only the one touched, because two of the four are
     * not independent: clearing the catalogue compacts the database, and compacting also changes what
     * the "catalogue" level declared a moment earlier. Re-reading only the pressed level would leave
     * the other three on stale numbers, which is the quickest way to make a successful operation look
     * broken.
     */
    fun clearStorage(level: StorageLevel) {
        if (_storageOps.value.busy != null) return
        viewModelScope.launch {
            _storageOps.value = _storageOps.value.copy(busy = level)
            val reclaimed = runCatching { maintenance.clear(level) }.getOrNull()
            val usage = runCatching { maintenance.usage() }.getOrNull() ?: _storageOps.value.usage
            _storageOps.value = _storageOps.value.copy(
                busy = null,
                usage = usage,
                freed = reclaimed?.let { _storageOps.value.freed + (level to it.freedBytes) }
                    ?: _storageOps.value.freed,
            )
        }
    }

    fun setKeepApkAfterInstall(keep: Boolean) =
        update { settingsRepository.setKeepApkAfterInstall(keep) }

    fun setImageCacheMaxMb(megabytes: Int) =
        update { settingsRepository.setImageCacheMaxMb(megabytes) }

    fun setCatalogRetention(retention: CatalogRetention) =
        update { settingsRepository.setCatalogRetention(retention) }

    fun setDownloadHistoryLimit(limit: DownloadHistoryLimit) =
        update { settingsRepository.setDownloadHistoryLimit(limit) }

    fun setAutoInstallAfterDownload(auto: Boolean) =
        update { settingsRepository.setAutoInstallAfterDownload(auto) }

    private val _reclaim = MutableStateFlow<ReclaimUiState>(ReclaimUiState.Idle)
    val reclaim: StateFlow<ReclaimUiState> = _reclaim.asStateFlow()

    /**
     * Compacts the database.
     *
     * The `runCatching` hides nothing: `VACUUM` can fail for lack of space — SQLite temporarily needs
     * as much as the database itself — and that is exactly the case in which the user is pressing this
     * button. A failure puts the button back as it was instead of leaving it spinning forever.
     */
    fun reclaimSpace() {
        if (_reclaim.value == ReclaimUiState.Running) return
        viewModelScope.launch {
            _reclaim.value = ReclaimUiState.Running
            _reclaim.value = runCatching { maintenance.reclaimSpace() }
                .fold(
                    onSuccess = { ReclaimUiState.Done(it.freedBytes) },
                    onFailure = { ReclaimUiState.Idle },
                )
            // Compacting changes the size of the "catalogue" level: without this line the screen
            // would say "40 MB freed" above a number that stayed identical.
            refreshStorageUsage()
        }
    }

    /**
     * What is active now and how the last attempt to update it went.
     *
     * A separate flow from [uiState] because it is not a setting: the user did not choose it and it
     * is not written anywhere. It changes when the app downloads a document, which is a moment of its
     * own.
     */
    val configStatus: StateFlow<RemoteConfigStatus> = remoteConfigRepository.status
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RemoteConfigStatus(),
        )

    private val _configRefreshing = MutableStateFlow(false)
    val configRefreshing: StateFlow<Boolean> = _configRefreshing.asStateFlow()

    /** The button pressed with the channel off: it must be said, or it would look inert. */
    private val _configRefreshBlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configRefreshBlocked: SharedFlow<Unit> = _configRefreshBlocked.asSharedFlow()

    fun refreshRemoteConfig() {
        if (_configRefreshing.value) return
        viewModelScope.launch {
            _configRefreshing.value = true
            // `refreshNow` does not throw on a network fault: that comes back as `Unreachable` and
            // ends up in the status row. `null` means one thing only — the channel is off.
            val outcome = runCatching { remoteConfigRepository.refreshNow() }
            if (outcome.isSuccess && outcome.getOrNull() == null) {
                _configRefreshBlocked.tryEmit(Unit)
            }
            _configRefreshing.value = false
        }
    }

    /**
     * The five remaining groups, in a single value.
     *
     * `combine` can type up to five flows, and there are now eight setting groups: either they nest,
     * or one moves to the `vararg` variant, which hands over an `Array<Any?>` and forces a cast per
     * field — that is, gives up the compiler exactly where a setting gets added, which is when it is
     * needed most.
     *
     * A private `data class` rather than nested `Triple`s: five positional fields with no names are
     * five chances to swap two of the same type, and `remoteConfig` and `notifications` are both
     * containers of `Boolean`.
     */
    private data class Rest(
        val network: NetworkSettings,
        val remoteConfig: RemoteConfigSettings,
        val search: SearchSettings,
        val notifications: NotificationSettings,
        val diagnostics: DiagnosticsSettings,
    )

    /**
     * Updates and version choice, in a single slot.
     *
     * With nine setting groups and five typed slots, one of the two nestings had to grow. This one
     * grows rather than [Rest] because the two groups end up in the **same screen section**: "how
     * often do I check" and "what counts as a version worth offering" are the same line of questions,
     * and whoever adds a field to one passes through here anyway.
     */
    private data class UpdatePolicy(
        val updates: UpdateSettings,
        val versions: VersionSettings,
    )

    private val updatePolicy = combine(
        settingsRepository.updates,
        settingsRepository.versions,
        ::UpdatePolicy,
    )

    private val rest = combine(
        settingsRepository.network,
        settingsRepository.remoteConfig,
        settingsRepository.search,
        settingsRepository.notifications,
        settingsRepository.diagnostics,
        ::Rest,
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.appearance,
        updatePolicy,
        settingsRepository.installation,
        settingsRepository.security,
        rest,
    ) { appearance, policy, installation, security, other ->
        SettingsUiState.Ready(
            appearance = appearance,
            updates = policy.updates,
            versions = policy.versions,
            installation = installation,
            security = security,
            network = other.network,
            remoteConfig = other.remoteConfig,
            search = other.search,
            notifications = other.notifications,
            diagnostics = other.diagnostics,
        )
    }
        .stateIn(
            scope = viewModelScope,
            // 5 s of grace: survives a rotation without re-reading the DataStore.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState.Loading,
        )

    /**
     * One-shot signal of a failed write.
     *
     * `DataStore.updateData` propagates `IOException`s: inside a `viewModelScope.launch` with no
     * try/catch, a full disk or a locked file becomes a crash on the tap of a switch. Worse still
     * would be swallowing the error silently — the user would see the setting revert on its own with
     * no explanation. So it is reported.
     *
     * `extraBufferCapacity = 1` with `tryEmit`: the emission must never suspend inside the catch, and
     * if two errors overlap, showing one is the right behaviour.
     */
    private val _writeFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val writeFailures: SharedFlow<Unit> = _writeFailures.asSharedFlow()

    fun setThemeMode(themeMode: ThemeMode) = update { settingsRepository.setThemeMode(themeMode) }

    fun setDynamicColor(enabled: Boolean) = update { settingsRepository.setDynamicColor(enabled) }

    /**
     * Persists the language. It does **not** apply the locale: `MainActivity` does that by observing
     * the setting, so there is exactly one place in the whole app that calls
     * `AppCompatDelegate.setApplicationLocales`, and no way for two different paths to leave the
     * applied locale and the stored one disagreeing.
     */
    fun setLanguage(language: SupportedLanguage?) = update {
        settingsRepository.setLanguageTag(language?.tag ?: SupportedLanguage.FOLLOW_SYSTEM_TAG)
    }

    fun setUpdateInterval(interval: UpdateInterval) =
        update { settingsRepository.setUpdateInterval(interval) }

    fun setUpdateOnlyWhenCharging(only: Boolean) =
        update { settingsRepository.setUpdateOnlyWhenCharging(only) }

    fun setAutoDownloadUpdates(auto: Boolean) =
        update { settingsRepository.setAutoDownloadUpdates(auto) }

    fun setAutoInstallUpdates(auto: Boolean) =
        update { settingsRepository.setAutoInstallUpdates(auto) }

    fun setAllowPreviewChannels(allow: Boolean) =
        update { settingsRepository.setAllowPreviewChannels(allow) }

    fun setMuteUpdateNotifications(mute: Boolean) =
        update { settingsRepository.setMuteUpdateNotifications(mute) }

    fun setAllowUnverifiedHash(allow: Boolean) =
        update { settingsRepository.setAllowUnverifiedHash(allow) }

    fun setAllowSignerMismatch(allow: Boolean) =
        update { settingsRepository.setAllowSignerMismatch(allow) }

    fun setBlockRemoteParsers(block: Boolean) =
        update { settingsRepository.setBlockRemoteParsers(block) }

    fun setBlockRemoteIndex(block: Boolean) =
        update { settingsRepository.setBlockRemoteIndex(block) }

    fun setBlockSelfUpdateCheck(block: Boolean) =
        update { settingsRepository.setBlockSelfUpdateCheck(block) }

    fun setShowNsfwContent(show: Boolean) =
        update { settingsRepository.setShowNsfwContent(show) }

    fun setSearchTimeout(timeout: Duration) =
        update { settingsRepository.setSearchTimeout(timeout) }

    fun setDefaultSort(sort: SearchSort) = update { settingsRepository.setDefaultSort(sort) }

    fun setDefaultContentKind(kind: ContentKind?) =
        update { settingsRepository.setDefaultContentKind(kind) }

    fun setMuteDownloadNotifications(mute: Boolean) =
        update { settingsRepository.setMuteDownloadNotifications(mute) }

    fun setMuteInstallNotifications(mute: Boolean) =
        update { settingsRepository.setMuteInstallNotifications(mute) }

    fun setMuteStoreAlerts(mute: Boolean) =
        update { settingsRepository.setMuteStoreAlerts(mute) }

    fun setDiagnosticsLogEnabled(enabled: Boolean) =
        update { settingsRepository.setDiagnosticsLogEnabled(enabled) }

    fun setMeteredNetworkAllowed(allowed: Boolean) =
        update { settingsRepository.setMeteredNetworkAllowed(allowed) }

    fun setChallengeStrategy(strategy: ChallengeStrategy) =
        update { settingsRepository.setChallengeStrategy(strategy) }

    fun setBlockUserAssistedChallenge(block: Boolean) =
        update { settingsRepository.setBlockUserAssistedChallenge(block) }

    fun setAllowWebAds(allow: Boolean) =
        update { settingsRepository.setAllowWebAds(allow) }

    private val _export = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val export: StateFlow<ExportUiState> = _export.asStateFlow()

    /**
     * Writes the report where the user chose.
     *
     * The destination arrives as a function rather than a `Uri`: opening a write stream needs a
     * `ContentResolver`, which is an Android object, and a ViewModel injecting one would need the
     * `Context` for something only the screen can do. This way the ViewModel produces the text and
     * the screen knows where to put it, which is exactly the split between the two.
     *
     * The `runCatching` covers the write, not building the report: that reads Room and the DataStore
     * and does not throw. What throws is the provider on the other side of the `Uri`, which can be
     * full, unmounted or read-only.
     */
    fun exportDiagnostics(write: suspend (String) -> Unit) {
        if (_export.value == ExportUiState.Preparing) return
        viewModelScope.launch {
            _export.value = ExportUiState.Preparing
            _export.value = runCatching { write(diagnostics.report()) }
                .fold(onSuccess = { ExportUiState.Done }, onFailure = { ExportUiState.Failed })
        }
    }

    private fun update(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (exception: IOException) {
                // A DataStore write error has no variants the UI could react to differently: full
                // disk, locked file and denied permission all lead to the same message and the same
                // remedy, retrying. What matters is that it does not pass in silence, because the
                // switch would revert on its own with no explanation.
                _writeFailures.tryEmit(Unit)
            }
        }
    }
}
