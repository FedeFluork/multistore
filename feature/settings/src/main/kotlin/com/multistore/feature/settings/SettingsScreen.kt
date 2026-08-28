package com.multistore.feature.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.ContentKind
import com.multistore.core.model.DiagnosticsSettings
import com.multistore.core.model.InstallSettings
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.NetworkSettings
import com.multistore.core.model.NotificationSettings
import com.multistore.core.model.RemoteConfigSettings
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SearchSort
import com.multistore.core.model.SecuritySettings
import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.StorageLevel
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.StoreId
import com.multistore.core.model.SupportedLanguage
import com.multistore.core.model.ThemeMode
import com.multistore.core.ui.InstallSources
import com.multistore.core.ui.ExternalLinks
import com.multistore.core.remoteconfig.ActiveConfig
import com.multistore.core.remoteconfig.ConfigRejection
import com.multistore.core.remoteconfig.FetchAttempt
import com.multistore.core.remoteconfig.RemoteConfigStatus
import com.multistore.core.model.UpdateInterval
import com.multistore.core.model.UpdateSettings
import com.multistore.core.model.VersionSettings
import com.multistore.core.ui.component.EmptyState
import com.multistore.core.ui.component.MultiStoreTopAppBar
import kotlin.time.Duration

/**
 * The Settings screen.
 *
 * The sections exist when the fields filling them exist, and the two grow together by construction:
 * `SettingsCoverageTest` fails if a `settings.proto` field has no entry here, and vice versa (rule 2).
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val reclaim by viewModel.reclaim.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val installers by viewModel.installers.collectAsStateWithLifecycle()
    val configStatus by viewModel.configStatus.collectAsStateWithLifecycle()
    val configRefreshing by viewModel.configRefreshing.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // The query does not live in the ViewModel, and that is not an oversight: it is **view** state —
    // it is read nowhere, it does not survive leaving the screen and it has nothing to save in the
    // DataStore. `rememberSaveable` covers rotation, which is the only case in which losing it would
    // be annoying.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val writeFailedMessage = stringResource(R.string.settings_write_failed)
    val permissionDeniedMessage = stringResource(R.string.settings_installer_permission_denied)
    val configBlockedMessage = stringResource(R.string.settings_config_refresh_blocked)

    val exportDoneMessage = stringResource(R.string.settings_diagnostics_export_done)
    val exportFailedMessage = stringResource(R.string.settings_diagnostics_export_failed)
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    /**
     * Where the report ends up: the user chooses, with the system's screen.
     *
     * `CreateDocument` and not a file of ours shared with a `FileProvider`: that way no storage
     * permission is needed — which this app does not ask for and must not — and the destination is
     * known only to whoever chose it. The MIME type is `text/plain` because the report is text, and
     * because that is what makes the file openable without hunting for an app.
     */
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE),
    ) { uri ->
        // `null` = the user cancelled the choice, which is not an error and does not deserve a
        // message: they have just decided not to export.
        if (uri != null) {
            viewModel.exportDiagnostics { report ->
                contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
                    ?: error("No write stream for $uri")
            }
        }
    }

    LaunchedEffect(export, exportDoneMessage, exportFailedMessage) {
        when (export) {
            ExportUiState.Done -> snackbarHostState.showSnackbar(exportDoneMessage)
            ExportUiState.Failed -> snackbarHostState.showSnackbar(exportFailedMessage)
            else -> Unit
        }
    }

    LaunchedEffect(viewModel, writeFailedMessage) {
        viewModel.writeFailures.collect {
            snackbarHostState.showSnackbar(writeFailedMessage)
        }
    }

    // Shizuku may have started while the app was elsewhere, and nobody tells us: availability is
    // re-read on returning here instead of staying that of last time.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshInstallers()
        onPauseOrDispose {}
    }

    LaunchedEffect(viewModel, permissionDeniedMessage) {
        viewModel.permissionDenials.collect {
            snackbarHostState.showSnackbar(permissionDeniedMessage)
        }
    }

    LaunchedEffect(viewModel, configBlockedMessage) {
        viewModel.configRefreshBlocked.collect {
            snackbarHostState.showSnackbar(configBlockedMessage)
        }
    }

    SettingsScreen(
        uiState = uiState,
        onThemeModeChange = viewModel::setThemeMode,
        onDynamicColorChange = viewModel::setDynamicColor,
        onLanguageChange = viewModel::setLanguage,
        onAllowUnverifiedHashChange = viewModel::setAllowUnverifiedHash,
        onAllowSignerMismatchChange = viewModel::setAllowSignerMismatch,
        onMeteredNetworkAllowedChange = viewModel::setMeteredNetworkAllowed,
        onChallengeStrategyChange = viewModel::setChallengeStrategy,
        onBlockUserAssistedChallengeChange = viewModel::setBlockUserAssistedChallenge,
        onAllowWebAdsChange = viewModel::setAllowWebAds,
        onShowNsfwContentChange = viewModel::setShowNsfwContent,
        onBlockRemoteParsersChange = viewModel::setBlockRemoteParsers,
        onBlockRemoteIndexChange = viewModel::setBlockRemoteIndex,
        onBlockSelfUpdateCheckChange = viewModel::setBlockSelfUpdateCheck,
        configStatus = configStatus,
        configRefreshing = configRefreshing,
        onRefreshRemoteConfig = viewModel::refreshRemoteConfig,
        onUpdateIntervalChange = viewModel::setUpdateInterval,
        onAllowPreviewChannelsChange = viewModel::setAllowPreviewChannels,
        onUpdateOnlyWhenChargingChange = viewModel::setUpdateOnlyWhenCharging,
        onMuteUpdateNotificationsChange = viewModel::setMuteUpdateNotifications,
        onAutoDownloadUpdatesChange = viewModel::setAutoDownloadUpdates,
        onAutoInstallUpdatesChange = viewModel::setAutoInstallUpdates,
        onInstallerPreferenceChange = viewModel::setInstallerPreference,
        installers = installers,
        stores = stores,
        onStoreEnabledChange = viewModel::setStoreEnabled,
        reclaim = reclaim,
        onReclaimSpace = viewModel::reclaimSpace,
        storage = storage,
        onClearStorage = viewModel::clearStorage,
        onKeepApkAfterInstallChange = viewModel::setKeepApkAfterInstall,
        onImageCacheMaxMbChange = viewModel::setImageCacheMaxMb,
        onCatalogRetentionChange = viewModel::setCatalogRetention,
        onSearchTimeoutChange = viewModel::setSearchTimeout,
        onDefaultContentKindChange = viewModel::setDefaultContentKind,
        onDefaultSortChange = viewModel::setDefaultSort,
        onMuteDownloadNotificationsChange = viewModel::setMuteDownloadNotifications,
        onMuteInstallNotificationsChange = viewModel::setMuteInstallNotifications,
        onMuteStoreAlertsChange = viewModel::setMuteStoreAlerts,
        onDiagnosticsLogEnabledChange = viewModel::setDiagnosticsLogEnabled,
        exporting = export == ExportUiState.Preparing,
        onExportDiagnostics = { createDocument.launch(exportFileName()) },
        onDonate = { url -> ExternalLinks.open(context, url) },
        canOpen = { url -> ExternalLinks.canOpen(context, url) },
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * The ViewModel-less version: it is the one put in previews and screenshot tests. Keeping the UI
 * stateless is what makes screenshots in light and dark possible without running Hilt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onLanguageChange: (SupportedLanguage?) -> Unit,
    onAllowUnverifiedHashChange: (Boolean) -> Unit,
    onAllowSignerMismatchChange: (Boolean) -> Unit,
    onMeteredNetworkAllowedChange: (Boolean) -> Unit,
    onChallengeStrategyChange: (ChallengeStrategy) -> Unit = {},
    onBlockUserAssistedChallengeChange: (Boolean) -> Unit = {},
    onAllowWebAdsChange: (Boolean) -> Unit = {},
    onShowNsfwContentChange: (Boolean) -> Unit = {},
    onBlockRemoteParsersChange: (Boolean) -> Unit = {},
    onBlockRemoteIndexChange: (Boolean) -> Unit = {},
    onBlockSelfUpdateCheckChange: (Boolean) -> Unit = {},
    configStatus: RemoteConfigStatus = RemoteConfigStatus(),
    configRefreshing: Boolean = false,
    onRefreshRemoteConfig: () -> Unit = {},
    onUpdateIntervalChange: (UpdateInterval) -> Unit = {},
    onAllowPreviewChannelsChange: (Boolean) -> Unit = {},
    onUpdateOnlyWhenChargingChange: (Boolean) -> Unit = {},
    onMuteUpdateNotificationsChange: (Boolean) -> Unit = {},
    onAutoDownloadUpdatesChange: (Boolean) -> Unit = {},
    onAutoInstallUpdatesChange: (Boolean) -> Unit = {},
    onInstallerPreferenceChange: (InstallerPreference) -> Unit = {},
    installers: InstallerAvailability = InstallerAvailability(),
    stores: List<StoreEntry> = emptyList(),
    onStoreEnabledChange: (StoreId, Boolean) -> Unit = { _, _ -> },
    reclaim: ReclaimUiState = ReclaimUiState.Idle,
    onReclaimSpace: () -> Unit = {},
    storage: StorageUiState = StorageUiState(),
    onClearStorage: (StorageLevel) -> Unit = {},
    onKeepApkAfterInstallChange: (Boolean) -> Unit = {},
    onImageCacheMaxMbChange: (Int) -> Unit = {},
    onCatalogRetentionChange: (CatalogRetention) -> Unit = {},
    onSearchTimeoutChange: (Duration) -> Unit = {},
    onDefaultContentKindChange: (ContentKind?) -> Unit = {},
    onDefaultSortChange: (SearchSort) -> Unit = {},
    onMuteDownloadNotificationsChange: (Boolean) -> Unit = {},
    onMuteInstallNotificationsChange: (Boolean) -> Unit = {},
    onMuteStoreAlertsChange: (Boolean) -> Unit = {},
    onDiagnosticsLogEnabledChange: (Boolean) -> Unit = {},
    exporting: Boolean = false,
    onExportDiagnostics: () -> Unit = {},
    /** Opens a donation address in the browser. */
    onDonate: (String) -> Unit = {},
    /**
     * `true` if something on this device would open that address.
     *
     * Passed from outside and not called in here because this screen is photographed in a Robolectric
     * test, where no browser is installed: with the resolution done here, the golden would show a
     * section that on a real device is always present.
     */
    canOpen: (String) -> Boolean = { true },
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    // Defaults for previews and screenshot tests: the screen stays composable without a ViewModel.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = { MultiStoreTopAppBar(title = stringResource(R.string.settings_title)) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        when (uiState) {
            SettingsUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is SettingsUiState.Ready -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // The field stays **outside** the scroll. Inside, it would disappear as soon as one
                // scrolled down — i.e. precisely when the list is long enough to need a search.
                SettingsSearchField(query = searchQuery, onQueryChange = onSearchQueryChange)

                val filter = rememberSettingsFilter(query = searchQuery, stores = stores)
                if (filter.nothingFound) {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = stringResource(R.string.settings_search_empty_title),
                        description = stringResource(R.string.settings_search_empty_message),
                    )
                    return@Scaffold
                }

                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceSection(
                        appearance = uiState.appearance,
                        filter = filter,
                        onThemeModeChange = onThemeModeChange,
                        onDynamicColorChange = onDynamicColorChange,
                        onLanguageChange = onLanguageChange,
                    )
                    StoresSection(
                        stores = stores,
                        filter = filter,
                        onStoreEnabledChange = onStoreEnabledChange,
                    )
                    ContentSection(
                        search = uiState.search,
                        filter = filter,
                        onShowNsfwContentChange = onShowNsfwContentChange,
                    )
                    SearchSection(
                        search = uiState.search,
                        filter = filter,
                        onSearchTimeoutChange = onSearchTimeoutChange,
                        onDefaultContentKindChange = onDefaultContentKindChange,
                        onDefaultSortChange = onDefaultSortChange,
                    )
                    NotificationsSection(
                        updates = uiState.updates,
                        notifications = uiState.notifications,
                        filter = filter,
                        onMuteUpdateNotificationsChange = onMuteUpdateNotificationsChange,
                        onMuteDownloadNotificationsChange = onMuteDownloadNotificationsChange,
                        onMuteInstallNotificationsChange = onMuteInstallNotificationsChange,
                        onMuteStoreAlertsChange = onMuteStoreAlertsChange,
                    )
                    UpdatesSection(
                        updates = uiState.updates,
                        versions = uiState.versions,
                        filter = filter,
                        silentInstallAvailable = installers.hasSilent,
                        onUpdateIntervalChange = onUpdateIntervalChange,
                        onAllowPreviewChannelsChange = onAllowPreviewChannelsChange,
                        onUpdateOnlyWhenChargingChange = onUpdateOnlyWhenChargingChange,
                        onAutoDownloadUpdatesChange = onAutoDownloadUpdatesChange,
                        onAutoInstallUpdatesChange = onAutoInstallUpdatesChange,
                        onBlockSelfUpdateCheckChange = onBlockSelfUpdateCheckChange,
                        remoteConfig = uiState.remoteConfig,
                    )
                    InstallationSection(
                        installation = uiState.installation,
                        filter = filter,
                        installers = installers,
                        onInstallerPreferenceChange = onInstallerPreferenceChange,
                    )
                    SecuritySection(
                        security = uiState.security,
                        filter = filter,
                        onAllowUnverifiedHashChange = onAllowUnverifiedHashChange,
                        onAllowSignerMismatchChange = onAllowSignerMismatchChange,
                    )
                    NetworkSection(
                        network = uiState.network,
                        filter = filter,
                        onMeteredNetworkAllowedChange = onMeteredNetworkAllowedChange,
                        onChallengeStrategyChange = onChallengeStrategyChange,
                        onBlockUserAssistedChallengeChange = onBlockUserAssistedChallengeChange,
                        onAllowWebAdsChange = onAllowWebAdsChange,
                    )
                    ConfigurationSection(
                        settings = uiState.remoteConfig,
                        filter = filter,
                        status = configStatus,
                        refreshing = configRefreshing,
                        onBlockRemoteParsersChange = onBlockRemoteParsersChange,
                        onBlockRemoteIndexChange = onBlockRemoteIndexChange,
                        onRefresh = onRefreshRemoteConfig,
                    )
                    StorageSection(
                        reclaim = reclaim,
                        storage = storage,
                        filter = filter,
                        onReclaimSpace = onReclaimSpace,
                        onClearStorage = onClearStorage,
                        onKeepApkAfterInstallChange = onKeepApkAfterInstallChange,
                        onImageCacheMaxMbChange = onImageCacheMaxMbChange,
                        onCatalogRetentionChange = onCatalogRetentionChange,
                    )
                    DiagnosticsSection(
                        diagnostics = uiState.diagnostics,
                        filter = filter,
                        exporting = exporting,
                        onDiagnosticsLogEnabledChange = onDiagnosticsLogEnabledChange,
                        onExport = onExportDiagnostics,
                    )
                    SupportSection(
                        filter = filter,
                        onDonate = onDonate,
                        canOpen = canOpen,
                    )
                    Box(modifier = Modifier.padding(bottom = LocalSpacing.current.extraLarge))
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    appearance: AppearanceSettings,
    filter: SettingsFilter,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onLanguageChange: (SupportedLanguage?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.APPEARANCE)) return
    val rows = filter.rowsOf(
        SettingKey.THEME_MODE,
        SettingKey.DYNAMIC_COLOR,
        SettingKey.LANGUAGE_TAG,
    )
    // Dynamic colour is a device capability, not a preference: below Android 12 the entry stays
    // visible but disabled, with the reason written. Hiding it would leave the user wondering why the
    // setting they read about is not there.
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.APPEARANCE.titleRes))

        SettingsRow(
            key = SettingKey.THEME_MODE,
            rows = rows,
            value = stringResource(appearance.themeMode.labelRes()),
            onClick = { showThemeDialog = true },
        )

        SettingsSwitchRow(
            key = SettingKey.DYNAMIC_COLOR,
            rows = rows,
            descriptionRes = R.string.settings_dynamic_color_unavailable.takeIf {
                !dynamicColorSupported
            },
            checked = appearance.dynamicColor && dynamicColorSupported,
            enabled = dynamicColorSupported,
            onCheckedChange = onDynamicColorChange,
        )

        SettingsRow(
            key = SettingKey.LANGUAGE_TAG,
            rows = rows,
            value = appearance.selectedLanguage?.endonym
                ?: stringResource(R.string.settings_language_option_system),
            onClick = { showLanguageDialog = true },
        )
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_theme_dialog_title),
            options = ThemeMode.entries.map { mode ->
                ChoiceOption(
                    label = stringResource(mode.labelRes()),
                    selected = mode == appearance.themeMode,
                    onSelect = { onThemeModeChange(mode) },
                )
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showLanguageDialog) {
        val systemOption = ChoiceOption(
            label = stringResource(R.string.settings_language_option_system),
            selected = appearance.selectedLanguage == null,
            onSelect = { onLanguageChange(null) },
        )
        val languageOptions = SupportedLanguage.entries.map { language ->
            ChoiceOption(
                // The endonym is not a string to translate: "Deutsch" stays "Deutsch" whatever the
                // interface language is, and that is exactly how whoever is looking for their own
                // language recognises it in a list they cannot read.
                label = language.endonym,
                selected = language == appearance.selectedLanguage,
                onSelect = { onLanguageChange(language) },
            )
        }
        SingleChoiceDialog(
            title = stringResource(R.string.settings_language_dialog_title),
            options = listOf(systemOption) + languageOptions,
            onDismiss = { showLanguageDialog = false },
        )
    }
}

/**
 * Updates: how often to look, and under what conditions.
 *
 * "Wi-Fi only" is absent, and that is not an oversight: the Network section already has "allow on
 * metered networks", which is the same question. Two overlapping switches are two values that can
 * diverge — see the note in `settings.proto`.
 */
@Composable
private fun UpdatesSection(
    updates: UpdateSettings,
    versions: VersionSettings,
    filter: SettingsFilter,
    silentInstallAvailable: Boolean,
    onUpdateIntervalChange: (UpdateInterval) -> Unit,
    onAllowPreviewChannelsChange: (Boolean) -> Unit,
    onUpdateOnlyWhenChargingChange: (Boolean) -> Unit,
    onAutoDownloadUpdatesChange: (Boolean) -> Unit,
    onAutoInstallUpdatesChange: (Boolean) -> Unit,
    onBlockSelfUpdateCheckChange: (Boolean) -> Unit,
    remoteConfig: RemoteConfigSettings,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.UPDATES)) return
    val rows = filter.rowsOf(
        SettingKey.UPDATE_INTERVAL,
        SettingKey.UPDATE_ONLY_WHEN_CHARGING,
        SettingKey.AUTO_DOWNLOAD_UPDATES,
        SettingKey.AUTO_INSTALL_UPDATES,
        SettingKey.ALLOW_PREVIEW_CHANNELS,
        SettingKey.BLOCK_SELF_UPDATE_CHECK,
    )
    var showIntervalDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.UPDATES.titleRes))

        SettingsRow(
            key = SettingKey.UPDATE_INTERVAL,
            rows = rows,
            value = stringResource(updates.interval.labelRes()),
            onClick = { showIntervalDialog = true },
        )

        // Disabled when the check is manual: "only while charging" describes **when** it starts by
        // itself, and if it does not start by itself it describes nothing. Staying visible instead of
        // disappearing is the same choice made for dynamic colour below Android 12.
        val automatic = updates.interval != UpdateInterval.MANUAL
        SettingsSwitchRow(
            key = SettingKey.UPDATE_ONLY_WHEN_CHARGING,
            rows = rows,
            descriptionRes = R.string.settings_update_manual_unavailable.takeIf { !automatic },
            checked = updates.onlyWhenCharging && automatic,
            enabled = automatic,
            onCheckedChange = onUpdateOnlyWhenChargingChange,
        )

        SettingsSwitchRow(
            key = SettingKey.AUTO_DOWNLOAD_UPDATES,
            rows = rows,
            descriptionRes = R.string.settings_update_manual_unavailable.takeIf { !automatic },
            checked = updates.autoDownload && automatic,
            enabled = automatic,
            onCheckedChange = onAutoDownloadUpdatesChange,
        )

        // Two different reasons this entry can be off, and they have to be said in different words:
        // "the automatic check is off" is solved two rows above, "you have no silent installer" is not
        // — it requires Shizuku or root, which is not something one switches on from here. A single
        // message would send half the users looking in the wrong place.
        SettingsSwitchRow(
            key = SettingKey.AUTO_INSTALL_UPDATES,
            rows = rows,
            descriptionRes = when {
                !automatic -> R.string.settings_update_manual_unavailable
                !silentInstallAvailable -> R.string.settings_update_auto_install_no_silent
                else -> null
            },
            checked = updates.autoInstall && automatic && silentInstallAvailable,
            enabled = automatic && silentInstallAvailable,
            onCheckedChange = onAutoInstallUpdatesChange,
        )

        // It is not disabled when the check is manual, unlike the three above: those describe **when**
        // the app starts by itself, this one **what it chooses** — and it chooses even when it is the
        // user pressing "Install", on an app they do not have yet.
        SettingsSwitchRow(
            key = SettingKey.ALLOW_PREVIEW_CHANNELS,
            rows = rows,
            checked = versions.allowPreviewChannels,
            enabled = true,
            onCheckedChange = onAllowPreviewChannelsChange,
        )

        // It sits among the updates and not among the remote configuration, although the document is
        // the same one: whoever asks "does MultiStore update itself?" looks here, not under an entry
        // that talks about CSS selectors. The switch governing the **document** lives over there, and
        // it is the one that wins: with the index blocked there is nothing to check.
        SettingsSwitchRow(
            key = SettingKey.BLOCK_SELF_UPDATE_CHECK,
            rows = rows,
            checked = remoteConfig.blockSelfUpdateCheck || remoteConfig.blockRemoteIndex,
            enabled = !remoteConfig.blockRemoteIndex,
            onCheckedChange = onBlockSelfUpdateCheckChange,
        )
    }

    if (showIntervalDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_update_interval_dialog_title),
            options = UpdateInterval.entries.map { interval ->
                ChoiceOption(
                    label = stringResource(interval.labelRes()),
                    selected = interval == updates.interval,
                    onSelect = { onUpdateIntervalChange(interval) },
                )
            },
            onDismiss = { showIntervalDialog = false },
        )
    }
}

/**
 * An interval's label.
 *
 * An exhaustive `when` and not a map: adding a value to the enum, the compiler asks what to write
 * instead of leaving an entry with no name in a list.
 */
@StringRes
private fun UpdateInterval.labelRes(): Int = when (this) {
    UpdateInterval.MANUAL -> R.string.settings_update_interval_manual
    UpdateInterval.EVERY_6_HOURS -> R.string.settings_update_interval_6_hours
    UpdateInterval.EVERY_12_HOURS -> R.string.settings_update_interval_12_hours
    UpdateInterval.DAILY -> R.string.settings_update_interval_daily
    UpdateInterval.WEEKLY -> R.string.settings_update_interval_weekly
}

/**
 * Notifications: the four things MultiStore can say when nobody is watching.
 *
 * The four entries come from two different domain groups — `mute_update_notifications` lives in
 * `UpdateSettings`, the other three in `NotificationSettings` — and the difference is intended: there
 * the groups follow **who reads** the value, here the question is "what does MultiStore send me?",
 * which has a single answer. See the note in `SETTINGS_REGISTRY`.
 *
 * The three new ones all have the same shape in their description, and it is not repetition: each says
 * **what it does not cover**. They are notices about what happens by itself, and whoever reads "say
 * when a download has finished" would otherwise expect a notification for the file they have just
 * asked for — and, its absence noted, conclude the switch does not work.
 */
@Composable
private fun NotificationsSection(
    updates: UpdateSettings,
    notifications: NotificationSettings,
    filter: SettingsFilter,
    onMuteUpdateNotificationsChange: (Boolean) -> Unit,
    onMuteDownloadNotificationsChange: (Boolean) -> Unit,
    onMuteInstallNotificationsChange: (Boolean) -> Unit,
    onMuteStoreAlertsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.NOTIFICATIONS)) return
    val rows = filter.rowsOf(
        SettingKey.MUTE_UPDATE_NOTIFICATIONS,
        SettingKey.MUTE_DOWNLOAD_NOTIFICATIONS,
        SettingKey.MUTE_INSTALL_NOTIFICATIONS,
        SettingKey.MUTE_STORE_ALERTS,
    )
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.NOTIFICATIONS.titleRes))

        SettingsSwitchRow(
            key = SettingKey.MUTE_UPDATE_NOTIFICATIONS,
            rows = rows,
            checked = updates.muteNotifications,
            enabled = true,
            onCheckedChange = onMuteUpdateNotificationsChange,
        )

        SettingsSwitchRow(
            key = SettingKey.MUTE_DOWNLOAD_NOTIFICATIONS,
            rows = rows,
            checked = notifications.muteDownloadComplete,
            enabled = true,
            onCheckedChange = onMuteDownloadNotificationsChange,
        )

        SettingsSwitchRow(
            key = SettingKey.MUTE_INSTALL_NOTIFICATIONS,
            rows = rows,
            checked = notifications.muteInstallResult,
            enabled = true,
            onCheckedChange = onMuteInstallNotificationsChange,
        )

        SettingsSwitchRow(
            key = SettingKey.MUTE_STORE_ALERTS,
            rows = rows,
            checked = notifications.muteStoreAlerts,
            enabled = true,
            onCheckedChange = onMuteStoreAlertsChange,
        )
    }
}

/**
 * Diagnostics: what the log records, and how to get it out.
 *
 * The promise from the start is diagnostics "local and **exportable by the user**", and until
 * recently the second half did not exist: `health_events` filled up and nobody could read it.
 *
 * The two rows' order is not accidental. The switch sits **above** the export because it decides what
 * will end up in the file, and whoever reads the export's description — "with the switch above on it
 * also contains the addresses" — has to have just seen it.
 */
@Composable
private fun DiagnosticsSection(
    diagnostics: DiagnosticsSettings,
    filter: SettingsFilter,
    exporting: Boolean,
    onDiagnosticsLogEnabledChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.DIAGNOSTICS)) return
    val rows = filter.rowsOf(SettingKey.DIAGNOSTICS_LOG_ENABLED)
    val exportAction = actionOf(SettingsActionKey.EXPORT_DIAGNOSTICS)
    val showExport = filter.shows(SettingsActionKey.EXPORT_DIAGNOSTICS)
    val spacing = LocalSpacing.current

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.DIAGNOSTICS.titleRes))

        SettingsSwitchRow(
            key = SettingKey.DIAGNOSTICS_LOG_ENABLED,
            rows = rows,
            checked = diagnostics.logRequests,
            enabled = true,
            onCheckedChange = onDiagnosticsLogEnabledChange,
        )

        if (showExport) Column(
            modifier = Modifier.padding(
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                bottom = spacing.small,
            ),
        ) {
            Text(
                text = stringResource(exportAction.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = spacing.small),
            )
            Text(
                text = stringResource(exportAction.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
            TextButton(
                onClick = onExport,
                enabled = !exporting,
                modifier = Modifier.padding(top = spacing.extraSmall),
            ) {
                Text(text = stringResource(exportAction.actionRes))
            }
        }
    }
}

/**
 * The two donation links, at the bottom of the screen.
 *
 * At the bottom because whoever gets there has already scrolled past everything else: a request
 * for support above the settings would be the first thing somebody who opened Settings to change
 * the theme sees. Two of them because Ko-fi and PayPal are not the same service to whoever pays —
 * different fees, different accounts, different countries — and offering a single route is
 * offering none to whoever does not have that one.
 *
 * They go through the registry, like exporting diagnostics: they open something and remember
 * nothing between launches, so they have no field in `settings.proto`. Being in [SETTINGS_ACTIONS]
 * is what makes the in-screen search find them, and what makes the guardrail that checks every
 * declared entry is actually **drawn** see them.
 *
 * Both buttons disappear when nothing would open them: `ExternalLinks.canOpen` rather than a `try`
 * around `startActivity`. On a device without a browser the button would be a promise that cannot
 * be kept, and this screen has nowhere to say so. Absent is more honest than broken.
 */
@Composable
internal fun SupportSection(
    filter: SettingsFilter,
    onDonate: (String) -> Unit,
    canOpen: (String) -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.SUPPORT)) return
    val spacing = LocalSpacing.current
    val donations = listOf(
        Triple(SettingsActionKey.DONATE_KOFI, KOFI_URL, actionOf(SettingsActionKey.DONATE_KOFI)),
        Triple(SettingsActionKey.DONATE_PAYPAL, PAYPAL_URL, actionOf(SettingsActionKey.DONATE_PAYPAL)),
    ).filter { (key, url, _) -> filter.shows(key) && canOpen(url) }
    if (donations.isEmpty()) return

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.SUPPORT.titleRes))
        Text(
            text = stringResource(R.string.settings_donate_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                bottom = spacing.medium,
            ),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
        ) {
            donations.forEach { (_, url, action) ->
                FilledTonalButton(
                    onClick = { onDonate(url) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(spacing.large),
                    )
                    Text(
                        text = stringResource(action.actionRes),
                        modifier = Modifier.padding(start = spacing.small),
                    )
                }
            }
        }
    }
}

/** Ko-fi. A constant and not a translated string: it is an address, not interface text. */
private const val KOFI_URL = "https://ko-fi.com/fedefluork"

/** PayPal, same reason. */
private const val PAYPAL_URL = "https://paypal.me/FedeFluork"

/**
 * Installation: who writes the package into the system.
 *
 * A section of its own rather than a row inside Security, even though the consumer is the same
 * `InstallRepositoryImpl`: those two entries **lower** a check, this one does not. The seven-step
 * pipeline is identical whichever channel delivers the bytes — there is no privileged path — and
 * putting this entry next to two opt-outs would say the opposite.
 *
 * An unusable channel stays **visible** in the list, marked. Hiding it would leave the user
 * wondering where the option they read about went; showing it lets them pick it, and at that
 * point the choice itself asks for the permission.
 */
@Composable
internal fun InstallationSection(
    installation: InstallSettings,
    filter: SettingsFilter,
    installers: InstallerAvailability,
    onInstallerPreferenceChange: (InstallerPreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.INSTALLATION)) return
    val rows = filter.rowsOf(SettingKey.INSTALLER_PREFERENCE)
    var showDialog by remember { mutableStateOf(false) }
    val unavailableFormat = stringResource(R.string.settings_installer_option_unavailable)

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.INSTALLATION.titleRes))

        SettingsRow(
            key = SettingKey.INSTALLER_PREFERENCE,
            rows = rows,
            value = stringResource(installation.preference.labelRes()),
            onClick = { showDialog = true },
        )

        UnknownSourcesRow(filter = filter)
    }

    if (showDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_installer_dialog_title),
            options = InstallerPreference.entries.map { preference ->
                val label = stringResource(preference.labelRes())
                val usable = preference.kind == null || preference.kind in installers.usable
                ChoiceOption(
                    label = if (usable) label else unavailableFormat.format(label),
                    selected = preference == installation.preference,
                    onSelect = { onInstallerPreferenceChange(preference) },
                )
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * The label of an installation method.
 *
 * An exhaustive `when` for the same reason as the interval: a fifth channel must not be able to
 * appear without a name.
 */
@StringRes
private fun InstallerPreference.labelRes(): Int = when (this) {
    InstallerPreference.AUTOMATIC -> R.string.settings_installer_option_automatic
    InstallerPreference.SESSION -> R.string.settings_installer_option_session
    InstallerPreference.SHIZUKU -> R.string.settings_installer_option_shizuku
    InstallerPreference.ROOT -> R.string.settings_installer_option_root
}

/**
 * Security: the only two places where the user can lower the pre-install verification.
 *
 * They are phrased positively ("allow") rather than negatively ("verify") because that is how they
 * are written in `settings.proto`, and there it is not a matter of style: in proto3 the zero value
 * of a `bool` **is** the default, so a field called "verify" would start off. See `SecuritySettings`.
 *
 * What is absent here is just as deliberate. Comparing the `packageName` of the file against the
 * listing has no switch: it is the defence against installing the wrong APK, and it is not
 * negotiable.
 */
@Composable
private fun SecuritySection(
    security: SecuritySettings,
    filter: SettingsFilter,
    onAllowUnverifiedHashChange: (Boolean) -> Unit,
    onAllowSignerMismatchChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.SECURITY)) return
    val rows = filter.rowsOf(
        SettingKey.ALLOW_UNVERIFIED_HASH,
        SettingKey.ALLOW_SIGNER_MISMATCH,
    )
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.SECURITY.titleRes))

        SettingsSwitchRow(
            key = SettingKey.ALLOW_UNVERIFIED_HASH,
            rows = rows,
            checked = security.allowUnverifiedHash,
            enabled = true,
            onCheckedChange = onAllowUnverifiedHashChange,
        )

        SettingsSwitchRow(
            key = SettingKey.ALLOW_SIGNER_MISMATCH,
            rows = rows,
            checked = security.allowSignerMismatch,
            enabled = true,
            onCheckedChange = onAllowSignerMismatchChange,
        )
    }
}

/**
 * What search is allowed to show.
 *
 * It sits right **below** the store list rather than among the network settings, because it is the
 * same question seen from the other side: there you choose who to ask, here what to accept back.
 *
 * The description says what the switch does **not** guarantee, and that is the part that costs most
 * to write and is worth most to read: only one store in seven labels adult content, and it does so
 * incompletely. Promising a clean catalogue would be the easy thing to write and the thing that
 * betrays the user at the first result that slips through.
 */
@Composable
private fun ContentSection(
    search: SearchSettings,
    filter: SettingsFilter,
    onShowNsfwContentChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.CONTENT)) return
    val rows = filter.rowsOf(SettingKey.SHOW_NSFW_CONTENT)
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.CONTENT.titleRes))

        SettingsSwitchRow(
            key = SettingKey.SHOW_NSFW_CONTENT,
            rows = rows,
            checked = search.showNsfwContent,
            enabled = true,
            onCheckedChange = onShowNsfwContentChange,
        )
    }
}

/**
 * Search: how long a store is waited for before giving up on it.
 *
 * A section with a single entry, for now, and not a placeholder: it is the only setting about
 * **how** searching happens rather than what is shown, and grouping it under "Network" with the
 * cache would make it unfindable to whoever looks for it starting from the symptom ("search takes
 * too long").
 *
 * The value shown is always a number, never "default": the default is one of the offered values,
 * so there is no state in which the user has to wonder how many seconds that is.
 */
@Composable
private fun SearchSection(
    search: SearchSettings,
    filter: SettingsFilter,
    onSearchTimeoutChange: (Duration) -> Unit,
    onDefaultContentKindChange: (ContentKind?) -> Unit,
    onDefaultSortChange: (SearchSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.SEARCH)) return
    val rows = filter.rowsOf(
        SettingKey.SEARCH_TIMEOUT,
        SettingKey.DEFAULT_CONTENT_KIND,
        SettingKey.DEFAULT_SORT,
    )
    var dialog by remember { mutableStateOf<SearchDialog?>(null) }

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.SEARCH.titleRes))

        SettingsRow(
            key = SettingKey.SEARCH_TIMEOUT,
            rows = rows,
            value = timeoutLabel(search.storeTimeout),
            onClick = { dialog = SearchDialog.TIMEOUT },
        )
        SettingsRow(
            key = SettingKey.DEFAULT_CONTENT_KIND,
            rows = rows,
            value = stringResource(contentKindLabel(search.defaultContentKind)),
            onClick = { dialog = SearchDialog.CONTENT_KIND },
        )
        SettingsRow(
            key = SettingKey.DEFAULT_SORT,
            rows = rows,
            value = stringResource(sortLabel(search.defaultSort)),
            onClick = { dialog = SearchDialog.SORT },
        )
    }

    when (dialog) {
        null -> Unit
        SearchDialog.TIMEOUT -> SingleChoiceDialog(
            title = stringResource(R.string.settings_search_timeout_dialog_title),
            options = SearchSettings.STORE_TIMEOUT_CHOICES.map { choice ->
                ChoiceOption(
                    label = timeoutLabel(choice),
                    selected = choice == search.storeTimeout,
                    onSelect = { onSearchTimeoutChange(choice) },
                )
            },
            onDismiss = { dialog = null },
        )

        SearchDialog.CONTENT_KIND -> SingleChoiceDialog(
            title = stringResource(entryOf(SettingKey.DEFAULT_CONTENT_KIND).labelRes),
            // `null` first: it is the value that hides nothing, and it is also the zero value of
            // the proto field. The list order and the enum order say the same thing.
            options = listOf(null, ContentKind.APP, ContentKind.GAME).map { choice ->
                ChoiceOption(
                    label = stringResource(contentKindLabel(choice)),
                    selected = choice == search.defaultContentKind,
                    onSelect = { onDefaultContentKindChange(choice) },
                )
            },
            onDismiss = { dialog = null },
        )

        SearchDialog.SORT -> SingleChoiceDialog(
            title = stringResource(entryOf(SettingKey.DEFAULT_SORT).labelRes),
            // `SELECTABLE` rather than `entries`: of the domain's six criteria, aggregated search
            // can compute three. See the table in `SearchSort`.
            options = SearchSort.SELECTABLE.map { choice ->
                ChoiceOption(
                    label = stringResource(sortLabel(choice)),
                    selected = choice == search.defaultSort,
                    onSelect = { onDefaultSortChange(choice) },
                )
            },
            onDismiss = { dialog = null },
        )
    }
}

/** Which of the three Search dialogs is open. */
private enum class SearchDialog { TIMEOUT, CONTENT_KIND, SORT }

@StringRes
private fun contentKindLabel(kind: ContentKind?): Int = when (kind) {
    ContentKind.APP -> R.string.settings_search_content_kind_apps
    ContentKind.GAME -> R.string.settings_search_content_kind_games
    // `UNKNOWN` is not a possible choice here — it means "the store does not say", which is a
    // row's answer and not a user's question — and counts as "everything".
    else -> R.string.settings_search_content_kind_all
}

@StringRes
private fun sortLabel(sort: SearchSort): Int = when (sort) {
    SearchSort.NAME -> R.string.settings_search_sort_name
    SearchSort.RATING -> R.string.settings_search_sort_rating
    else -> R.string.settings_search_sort_relevance
}

/**
 * "8 seconds", in the plural form of the current language.
 *
 * `pluralStringResource` rather than a `format` with an "s": in German the singular and plural of
 * "Sekunde" do not differ by a trailing letter, and the one singular value in the list — should one
 * ever appear — would read "1 Sekunden".
 */
@Composable
private fun timeoutLabel(timeout: Duration): String {
    val seconds = timeout.inWholeSeconds.toInt()
    return pluralStringResource(R.plurals.settings_search_timeout_seconds, seconds, seconds)
}

/**
 * Network: how much traffic may be spent, and how far the app may go when a store bars the way.
 *
 * The two challenge entries live here and not in "Security" because they loosen no verification:
 * whatever comes down those paths goes through the same pre-install pipeline as the other seven
 * stores. What changes is **how much network work** the app is willing to do to reach the file,
 * which is exactly the question this section asks.
 */
@Composable
internal fun NetworkSection(
    network: NetworkSettings,
    filter: SettingsFilter = SettingsFilter.NONE,
    onMeteredNetworkAllowedChange: (Boolean) -> Unit,
    onChallengeStrategyChange: (ChallengeStrategy) -> Unit = {},
    onBlockUserAssistedChallengeChange: (Boolean) -> Unit = {},
    onAllowWebAdsChange: (Boolean) -> Unit = {},
    stores: List<StoreEntry> = emptyList(),
    onStoreEnabledChange: (StoreId, Boolean) -> Unit = { _, _ -> },
    reclaim: ReclaimUiState = ReclaimUiState.Idle,
    onReclaimSpace: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.NETWORK)) return
    val rows = filter.rowsOf(
        SettingKey.METERED_NETWORK_ALLOWED,
        SettingKey.CHALLENGE_STRATEGY,
        SettingKey.BLOCK_USER_ASSISTED_CHALLENGE,
        SettingKey.ALLOW_WEB_ADS,
    )
    var showStrategyDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.NETWORK.titleRes))

        SettingsSwitchRow(
            key = SettingKey.METERED_NETWORK_ALLOWED,
            rows = rows,
            checked = network.meteredNetworkAllowed,
            enabled = true,
            onCheckedChange = onMeteredNetworkAllowedChange,
        )

        SettingsRow(
            key = SettingKey.CHALLENGE_STRATEGY,
            rows = rows,
            value = stringResource(network.challengeStrategy.labelRes()),
            onClick = { showStrategyDialog = true },
        )

        SettingsSwitchRow(
            key = SettingKey.BLOCK_USER_ASSISTED_CHALLENGE,
            rows = rows,
            checked = network.blockUserAssistedChallenge,
            enabled = true,
            onCheckedChange = onBlockUserAssistedChallengeChange,
        )

        SettingsSwitchRow(
            key = SettingKey.ALLOW_WEB_ADS,
            rows = rows,
            checked = network.allowWebAds,
            enabled = true,
            onCheckedChange = onAllowWebAdsChange,
        )
    }

    if (showStrategyDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_challenge_strategy_dialog_title),
            options = ChallengeStrategy.entries.map { strategy ->
                ChoiceOption(
                    label = stringResource(strategy.labelRes()),
                    selected = strategy == network.challengeStrategy,
                    onSelect = { onChallengeStrategyChange(strategy) },
                )
            },
            onDismiss = { showStrategyDialog = false },
        )
    }
}

/**
 * The label of a strategy.
 *
 * The `when` is exhaustive and has no `else` for the same reason as [storeDescriptionRes]: if a
 * fourth strategy ever arrived, this function would not compile until somebody decided what to
 * call it in five languages — instead of showing an empty row.
 */
@StringRes
private fun ChallengeStrategy.labelRes(): Int = when (this) {
    ChallengeStrategy.BALANCED -> R.string.settings_challenge_strategy_balanced
    ChallengeStrategy.CONSERVATIVE -> R.string.settings_challenge_strategy_conservative
    ChallengeStrategy.AGGRESSIVE -> R.string.settings_challenge_strategy_aggressive
}

/**
 * Which stores to query.
 *
 * The section does **not** appear when the list is empty, and that is not a case worth hiding: it
 * happens in the instant between the screen opening and Room's first emission. A "Stores" heading
 * above nothing would make it look as if the app knew of none.
 *
 * The store name does not go through `strings.xml`, and that is the one deliberate exception to the
 * no-hardcoded-strings rule: "APKMirror" is a trademark and is spelled the same in all five
 * languages. What is translated is the description, which [storeDescriptionRes] resolves per
 * [StoreId] — and the `MultiStoreComposeHardcodedText` lint does not complain because that name
 * arrives as a value, not as a literal.
 */
@Composable
internal fun StoresSection(
    stores: List<StoreEntry>,
    onStoreEnabledChange: (StoreId, Boolean) -> Unit,
    filter: SettingsFilter = SettingsFilter.NONE,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.STORES)) return
    // Filtering here rather than in the caller: the section has a description of its own above the
    // rows, and showing it above zero stores is the same as showing an empty heading.
    val visible = stores.filter { filter.shows(it.storeId) }
    if (visible.isEmpty()) return
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.STORES.titleRes))
        PlainSettingsRow(
            label = stringResource(R.string.settings_stores_choose_label),
            description = stringResource(R.string.settings_stores_description),
            value = stringResource(
                R.string.settings_stores_active_count,
                stores.count { it.enabled },
                stores.size,
            ),
            onClick = { showDialog = true },
        )
    }

    if (showDialog) {
        StorePickerDialog(
            stores = stores,
            onDismiss = { showDialog = false },
            onSave = { enabled ->
                showDialog = false
                // Only what actually changed: every write touches a Room row and makes the flow
                // that search observes re-emit. Saving without having changed anything must not
                // cost nine rewrites.
                stores.filter { enabled[it.storeId] != it.enabled }
                    .forEach { onStoreEnabledChange(it.storeId, enabled.getValue(it.storeId)) }
            },
        )
    }
}

/**
 * The store list in a dialog, with Save and Cancel.
 *
 * Not inline any more because they were **nine rows with nine descriptions** in the middle of a
 * screen that already has twenty: the section took up more room than all the others put together,
 * and one scrolled through it on the way somewhere else. In a dialog the list stays whole — it is
 * not hidden, it is one tap away — and the screen becomes readable again.
 *
 * Save and Cancel rather than a switch that writes immediately, because turning a store off
 * **changes what search queries**, and whoever turns three off does it in one go. With immediate
 * writes that gesture would be three Room rewrites and three re-emissions of the flow search
 * observes, each with its own recomposition — and no way to change one's mind. Here the pending
 * state lives in the dialog, Cancel throws it away, and Save writes **only what actually changed**.
 */
@Composable
internal fun StorePickerDialog(
    stores: List<StoreEntry>,
    onDismiss: () -> Unit,
    onSave: (Map<StoreId, Boolean>) -> Unit,
) {
    // `remember(stores)` rather than `remember {}`: if the catalogue changes underneath — a sync
    // that adds a store — the pending state must restart from what is there, not from a snapshot
    // of a list that no longer exists.
    val pending = remember(stores) {
        mutableStateMapOf<StoreId, Boolean>().apply {
            stores.forEach { put(it.storeId, it.enabled) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(SettingsSection.STORES.titleRes)) },
        text = {
            // Scrollable: nine stores with their descriptions do not fit the height a dialog has
            // on a phone, and without this the last rows would be unreachable.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                stores.forEach { store ->
                    StoreRow(
                        store = store,
                        checked = pending[store.storeId] ?: store.enabled,
                        onEnabledChange = { pending[store.storeId] = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(pending.toMap()) }) {
                Text(text = stringResource(R.string.settings_stores_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_dialog_dismiss))
            }
        },
    )
}

@Composable
private fun StoreRow(
    store: StoreEntry,
    checked: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    // With no translated description the host is shown: it still says where the files come from,
    // which is the thing that matters most in an app that installs APKs.
    val description = storeDescriptionRes(store.storeId)?.let { stringResource(it) } ?: store.host

    Column(modifier = modifier) {
        SettingsSwitchRow(
            label = store.displayName,
            description = description,
            checked = checked,
            enabled = true,
            onCheckedChange = onEnabledChange,
        )
        // The breaker state is shown **only when it is not the normal one**: an "all fine" row next
        // to every store would be noise, and would make the one row that does matter less visible
        // when it appears.
        storeStateRes(store.health.state)?.let { stateRes ->
            Text(
                text = stringResource(stateRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(
                    start = spacing.screenHorizontal,
                    end = spacing.screenHorizontal,
                    bottom = spacing.small,
                ),
            )
        }
    }
}

/** `null` when the store is healthy: the extra row appears only if there is something to say. */
@androidx.annotation.StringRes
private fun storeStateRes(state: StoreHealthState): Int? = when (state) {
    StoreHealthState.OPEN -> R.string.settings_store_state_open
    StoreHealthState.DEGRADED -> R.string.settings_store_state_degraded
    // `HALF_OPEN` is a probe in flight, not a fault to announce: it lasts one request.
    StoreHealthState.CLOSED, StoreHealthState.HALF_OPEN -> null
}

/**
 * Storage: four levels with their size, three settings, and one compaction.
 *
 * Four levels rather than a single number because the measurement says one number would not do:
 * catalogue 62.3 MB, staged APKs 28.2 MB, images 4.3 MB, pages 1.5 MB. Those are four different
 * orders of magnitude with four different rebuild costs — icons come back on the first scroll, the
 * F-Droid catalogue is 18 MB compressed to re-download — and a single total would not let anyone
 * choose.
 *
 * The levels come first, the settings after. Whoever opens this section nearly always arrives with
 * one question — "what is taking up the space?" — and the three settings answer the next one, not
 * that one.
 */
@Composable
internal fun StorageSection(
    reclaim: ReclaimUiState,
    onReclaimSpace: () -> Unit,
    storage: StorageUiState = StorageUiState(),
    onClearStorage: (StorageLevel) -> Unit = {},
    onKeepApkAfterInstallChange: (Boolean) -> Unit = {},
    onImageCacheMaxMbChange: (Int) -> Unit = {},
    onCatalogRetentionChange: (CatalogRetention) -> Unit = {},
    filter: SettingsFilter = SettingsFilter.NONE,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.STORAGE)) return
    val rows = filter.rowsOf(
        SettingKey.KEEP_APK_AFTER_INSTALL,
        SettingKey.CATALOG_RETENTION,
        SettingKey.IMAGE_CACHE_MAX_MB,
    )
    val reclaimAction = actionOf(SettingsActionKey.RECLAIM_SPACE)
    val spacing = LocalSpacing.current
    var showRetentionDialog by remember { mutableStateOf(false) }
    var showImageCacheDialog by remember { mutableStateOf(false) }
    // Only the catalogue asks for confirmation, and that is not caution sprinkled at random: it is
    // the only one of the four whose rebuild costs **traffic** — 18 MB compressed for F-Droid alone
    // — and the only one that can be pressed on a metered network with nothing saying so.
    var confirmCatalog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.STORAGE.titleRes))

        StorageLevelRow(
            action = SettingsActionKey.CLEAR_CATALOG,
            filter = filter,
            storage = storage,
            onClear = { confirmCatalog = true },
        )
        StorageLevelRow(
            action = SettingsActionKey.CLEAR_STAGED_APKS,
            filter = filter,
            storage = storage,
            onClear = onClearStorage,
        )
        StorageLevelRow(
            action = SettingsActionKey.CLEAR_IMAGES,
            filter = filter,
            storage = storage,
            onClear = onClearStorage,
        )
        StorageLevelRow(
            action = SettingsActionKey.CLEAR_PAGES,
            filter = filter,
            storage = storage,
            onClear = onClearStorage,
        )

        SettingsSwitchRow(
            key = SettingKey.KEEP_APK_AFTER_INSTALL,
            rows = rows,
            checked = storage.settings.keepApkAfterInstall,
            enabled = true,
            onCheckedChange = onKeepApkAfterInstallChange,
        )

        SettingsRow(
            key = SettingKey.CATALOG_RETENTION,
            rows = rows,
            value = stringResource(storage.settings.catalogRetention.labelRes()),
            onClick = { showRetentionDialog = true },
        )

        SettingsRow(
            key = SettingKey.IMAGE_CACHE_MAX_MB,
            rows = rows,
            value = megabytesLabel(storage.settings.imageCacheMaxBytes),
            onClick = { showImageCacheDialog = true },
        )

        if (!filter.shows(SettingsActionKey.RECLAIM_SPACE)) return@Column

        Column(
            modifier = Modifier.padding(
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                bottom = spacing.small,
            ),
        ) {
            Text(
                text = stringResource(reclaimAction.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(reclaimAction.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
            when (reclaim) {
                ReclaimUiState.Idle -> TextButton(
                    onClick = onReclaimSpace,
                    modifier = Modifier.padding(top = spacing.extraSmall),
                ) {
                    Text(text = stringResource(reclaimAction.actionRes))
                }

                ReclaimUiState.Running -> Text(
                    text = stringResource(R.string.settings_reclaim_space_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = spacing.small),
                )

                is ReclaimUiState.Done -> Text(
                    // Zero bytes freed is not a saving of zero: it is "there was nothing to do",
                    // and saying it with the same sentence would confuse a successful outcome with
                    // a pointless one.
                    text = if (reclaim.freedBytes > 0) {
                        stringResource(
                            R.string.settings_reclaim_space_freed,
                            android.text.format.Formatter.formatShortFileSize(
                                LocalContext.current,
                                reclaim.freedBytes,
                            ),
                        )
                    } else {
                        stringResource(R.string.settings_reclaim_space_nothing)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = spacing.small),
                )
            }
        }
    }

    if (showRetentionDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_catalog_retention_dialog_title),
            options = CatalogRetention.entries.map { retention ->
                ChoiceOption(
                    label = stringResource(retention.labelRes()),
                    selected = retention == storage.settings.catalogRetention,
                    onSelect = { onCatalogRetentionChange(retention) },
                )
            },
            onDismiss = { showRetentionDialog = false },
        )
    }

    if (showImageCacheDialog) {
        val selected = storage.settings.imageCacheMaxBytes
        SingleChoiceDialog(
            title = stringResource(R.string.settings_image_cache_dialog_title),
            options = StorageSettings.IMAGE_CACHE_MB_CHOICES.map { megabytes ->
                ChoiceOption(
                    label = megabytesLabel(StorageSettings.megabytes(megabytes)),
                    selected = StorageSettings.megabytes(megabytes) == selected,
                    onSelect = { onImageCacheMaxMbChange(megabytes) },
                )
            },
            onDismiss = { showImageCacheDialog = false },
        )
    }

    if (confirmCatalog) {
        AlertDialog(
            onDismissRequest = { confirmCatalog = false },
            title = { Text(text = stringResource(R.string.settings_storage_catalog_confirm_title)) },
            text = { Text(text = stringResource(R.string.settings_storage_catalog_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCatalog = false
                        onClearStorage(StorageLevel.CATALOG)
                    },
                ) {
                    Text(text = stringResource(R.string.settings_storage_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCatalog = false }) {
                    Text(text = stringResource(R.string.settings_storage_confirm_cancel))
                }
            },
        )
    }
}

/**
 * One level's row: how much it takes, what it holds, and the button that empties it.
 *
 * The size is on the **same row** as the label rather than in a summary at the top. At the top it
 * would be a total, and a total does not answer the question one arrives here with: not "how much
 * does the app take" — the system settings already say that — but "which piece, and what does
 * throwing it away cost".
 *
 * The outcome stays on screen after emptying rather than disappearing: the four buttons get pressed
 * one after another, and an outcome that vanishes on the next tap removes the very number that was
 * being compared.
 */
@Composable
private fun StorageLevelRow(
    action: SettingsActionKey,
    filter: SettingsFilter,
    storage: StorageUiState,
    onClear: (StorageLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(action)) return
    val level = action.storageLevel() ?: return
    val entry = actionOf(action)
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val busy = storage.busy == level
    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            bottom = spacing.small,
        ),
    ) {
        Text(
            text = stringResource(entry.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = android.text.format.Formatter.formatShortFileSize(
                context,
                storage.usage.bytesOf(level),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(entry.descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.extraSmall),
        )
        if (busy) {
            Text(
                text = stringResource(R.string.settings_storage_clearing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = spacing.small),
            )
        } else {
            TextButton(
                onClick = { onClear(level) },
                enabled = storage.busy == null,
                modifier = Modifier.padding(top = spacing.extraSmall),
            ) {
                Text(text = stringResource(entry.actionRes))
            }
        }
        storage.freed[level]?.let { freed ->
            Text(
                // Zero bytes freed is not a saving of zero: it is "there was nothing to do", the
                // same distinction already made for "Reclaim space".
                text = if (freed > 0) {
                    stringResource(
                        R.string.settings_storage_freed,
                        android.text.format.Formatter.formatShortFileSize(context, freed),
                    )
                } else {
                    stringResource(R.string.settings_storage_nothing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * "200 MB", with the current language's separator.
 *
 * `Formatter.formatShortFileSize` rather than a `format` with "MB": it is the same function that
 * writes the sizes of the four levels a few rows above, and using a different one here would give a
 * cap written one way and a usage written another — on the same screen, one under the other.
 */
@Composable
private fun megabytesLabel(bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)

/**
 * The label of a retention choice.
 *
 * An exhaustive `when` with no `else`, as for [storeDescriptionRes]: a fifth choice would not
 * compile until somebody decided what to call it in five languages.
 */
@StringRes
private fun CatalogRetention.labelRes(): Int = when (this) {
    CatalogRetention.SEVEN_DAYS -> R.string.settings_catalog_retention_7_days
    CatalogRetention.THIRTY_DAYS -> R.string.settings_catalog_retention_30_days
    CatalogRetention.NINETY_DAYS -> R.string.settings_catalog_retention_90_days
    CatalogRetention.KEEP -> R.string.settings_catalog_retention_keep
}

/**
 * Remote configuration: a switch, a button, and above all **what is going on**.
 *
 * The status rows are not decoration. This is the only part of MultiStore whose behaviour can change
 * without the user having updated anything, and with no screen showing it it would also be the only
 * part that changes **silently**. The three things it reports are the three that can break
 * separately:
 *
 *  - **what is in use now** — the compiled defaults, or a document and for how many stores;
 *  - **how the last attempt went** — and an accepted document says explicitly that it will be in
 *    use *at the next launch*, because otherwise "updated" would appear above an app still using
 *    the previous one;
 *  - **what was not applied** — keys this version does not know, stores whose override had the
 *    wrong type. Those are the two silent failures: valid signature, accepted document, no effect.
 */
@Composable
private fun ConfigurationSection(
    settings: RemoteConfigSettings,
    filter: SettingsFilter,
    status: RemoteConfigStatus,
    refreshing: Boolean,
    onBlockRemoteParsersChange: (Boolean) -> Unit,
    onBlockRemoteIndexChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filter.shows(SettingsSection.CONFIGURATION)) return
    val rows = filter.rowsOf(
        SettingKey.BLOCK_REMOTE_PARSERS,
        SettingKey.BLOCK_REMOTE_INDEX,
    )
    val refreshAction = actionOf(SettingsActionKey.REFRESH_REMOTE_CONFIG)
    // The status rows accompany the button, so they follow it: with a search that matches only the
    // "reject fixes" switch, showing three diagnostic rows underneath about a button that is not
    // there would be an answer to a question nobody asked.
    val showRefresh = filter.shows(SettingsActionKey.REFRESH_REMOTE_CONFIG)
    val spacing = LocalSpacing.current
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(SettingsSection.CONFIGURATION.titleRes))

        SettingsSwitchRow(
            key = SettingKey.BLOCK_REMOTE_PARSERS,
            rows = rows,
            checked = settings.blockRemoteParsers,
            enabled = true,
            onCheckedChange = onBlockRemoteParsersChange,
        )

        SettingsSwitchRow(
            key = SettingKey.BLOCK_REMOTE_INDEX,
            rows = rows,
            checked = settings.blockRemoteIndex,
            enabled = true,
            onCheckedChange = onBlockRemoteIndexChange,
        )

        if (showRefresh) Column(
            modifier = Modifier.padding(
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                bottom = spacing.small,
            ),
        ) {
            ConfigStatusLine(text = activeConfigText(status.active))

            status.lastAttempt?.let { attempt ->
                ConfigStatusLine(text = stringResource(attempt.messageRes()))
            }

            // Unknown keys and unknown stores are the same news — "the document names something
            // this version cannot apply" — and are counted together.
            val unapplied = status.ignoredKeys + status.unknownStores.map { "stores.$it" }
            if (unapplied.isNotEmpty()) {
                ConfigStatusLine(
                    text = pluralStringResource(
                        R.plurals.settings_config_ignored_keys,
                        unapplied.size,
                        unapplied.size,
                    ) + " " + unapplied.joinToString(", "),
                )
            }

            if (status.rejectedStores.isNotEmpty()) {
                ConfigStatusLine(
                    text = pluralStringResource(
                        R.plurals.settings_config_rejected_stores,
                        status.rejectedStores.size,
                        status.rejectedStores.size,
                    ),
                )
            }

            Text(
                text = stringResource(refreshAction.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = spacing.small),
            )
            Text(
                text = stringResource(refreshAction.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
            TextButton(
                onClick = onRefresh,
                enabled = !refreshing,
                modifier = Modifier.padding(top = spacing.extraSmall),
            ) {
                Text(text = stringResource(refreshAction.actionRes))
            }
        }
    }
}

@Composable
private fun ConfigStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = LocalSpacing.current.extraSmall),
    )
}

@Composable
private fun activeConfigText(active: ActiveConfig): String = when (active) {
    ActiveConfig.CompiledDefaults -> stringResource(R.string.settings_config_active_defaults)
    is ActiveConfig.Applied -> pluralStringResource(
        R.plurals.settings_config_active_stores,
        active.stores.size,
        active.stores.size,
    )
}

/**
 * Six outcomes, four sentences.
 *
 * The grouping is not translation laziness: what changes for the reader are the **possible
 * actions**, and there are four. Missing signature, wrong signature and unknown algorithm all lead
 * to the same conclusion — that file did not come from us — while the distinction between the three
 * serves the diagnosis, which is indeed kept whole in [ConfigRejection]. A document that verified
 * but was not written to disk is reported as "not reached", because the remedy is the same: retry.
 */
@StringRes
private fun FetchAttempt.messageRes(): Int = when (this) {
    is FetchAttempt.Accepted -> R.string.settings_config_attempt_accepted
    is FetchAttempt.NotModified -> R.string.settings_config_attempt_unchanged
    is FetchAttempt.Unreachable, is FetchAttempt.NotStored ->
        R.string.settings_config_attempt_unreachable
    is FetchAttempt.Rejected -> when (reason) {
        ConfigRejection.MISSING_SIGNATURE,
        ConfigRejection.BAD_SIGNATURE,
        ConfigRejection.UNSUPPORTED_ALGORITHM,
        -> R.string.settings_config_attempt_bad_signature

        ConfigRejection.MALFORMED_ENVELOPE,
        ConfigRejection.MALFORMED_PAYLOAD,
        -> R.string.settings_config_attempt_malformed

        ConfigRejection.UNSUPPORTED_SCHEMA -> R.string.settings_config_attempt_too_new
    }
}

/**
 * The suggested name for the report file.
 *
 * This is not interface text and does not belong in `strings.xml`: it is a **file name**, and a
 * report called `multistore-diagnostica.txt` on an Italian phone and `multistore-diagnose.txt` on a
 * German one is a report whoever receives it does not recognise. The date distinguishes two exports
 * on the same day without overwriting them — and tells whoever reads the file six months later what
 * it is about.
 *
 * `System.currentTimeMillis` rather than the injected clock: nothing about time is under test here,
 * and the value only composes a name the user can change anyway in the system screen that opens
 * right after.
 */
private fun exportFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
        .format(java.util.Date(System.currentTimeMillis()))
    return "multistore-diagnostics-$stamp.txt"
}

/** Plain text: makes the report openable without hunting for an app that can read it. */
private const val EXPORT_MIME_TYPE = "text/plain"

// ------------------------------------------------------------------------- local components

/**
 * A section's **visible** entries, in the order they appear.
 *
 * It keeps two decisions that must stay consistent together: which rows to draw, and where the
 * divider goes. Keeping them apart was the road to this screen's most likely defect — a search that
 * hides a section's first entry and leaves the grey line dangling under the heading, or two dividers
 * side by side where a filtered-out entry used to be.
 */
private class SectionRows(private val visible: List<SettingKey>) {

    fun shows(key: SettingKey): Boolean = key in visible

    /** A divider above every entry except the **first visible** one, not the first declared. */
    fun divided(key: SettingKey): Boolean = visible.firstOrNull() != key
}

private fun SettingsFilter.rowsOf(vararg keys: SettingKey): SectionRows =
    SectionRows(keys.filter(::shows))

/**
 * The field that filters the settings.
 *
 * There is no `ImeAction.Search` and no key to press: the filter applies on every character, because
 * there is nothing to go and fetch — the entries are all in memory already. A "search" action would
 * suggest a wait that does not exist.
 */
@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text(text = stringResource(R.string.settings_search_field_label)) },
        leadingIcon = {
            // Decorative: the field's label already says what it does, and announcing it would
            // make TalkBack users hear it twice.
            Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.settings_search_field_clear),
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.small),
    )
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.large,
            bottom = spacing.small,
        ),
    )
}

/**
 * An entry that opens a choice.
 *
 * The label and description come from the **registry**, not from this call, and that is the half
 * that makes the in-screen search correct: the filter compares the registry's text, so a row showing
 * different text would disappear when searching for a word the user has in front of them. What the
 * call can still decide is [descriptionRes], for the cases where the explanation changes at runtime
 * — "not available on this device" — and even there it is one more string, not a different one.
 */
@Composable
private fun SettingsRow(
    key: SettingKey,
    rows: SectionRows,
    value: String,
    onClick: () -> Unit,
    @StringRes descriptionRes: Int? = null,
    modifier: Modifier = Modifier,
) {
    if (!rows.shows(key)) return
    if (rows.divided(key)) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val entry = entryOf(key)
    val label = stringResource(entry.labelRes)
    val description = stringResource(descriptionRes ?: entry.descriptionRes)
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A row that looks exactly like [SettingsRow] but has **no registry entry**.
 *
 * There is only one, and it is the one that opens the store list. It cannot be a [SettingKey]
 * because store enablement does not live in `settings.proto` — it is in Room, column `enabled` — and
 * `SettingsCoverageTest` requires every registry key to have a matching proto field. Adding it to
 * the registry would mean adding a proto field nobody writes, which is precisely the hidden state
 * that test exists to catch.
 */
@Composable
private fun PlainSettingsRow(
    label: String,
    description: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The switch of a registry entry. See the note on [SettingsRow]. */
@Composable
private fun SettingsSwitchRow(
    key: SettingKey,
    rows: SectionRows,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @StringRes descriptionRes: Int? = null,
    modifier: Modifier = Modifier,
) {
    if (!rows.shows(key)) return
    if (rows.divided(key)) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val entry = entryOf(key)
    SettingsSwitchRow(
        label = stringResource(entry.labelRes),
        description = stringResource(descriptionRes ?: entry.descriptionRes),
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
    )
}

/**
 * A switch with a free-form label and description.
 *
 * It exists for rows that are **not** registry entries, and there is only one kind: stores. Their
 * enablement lives in Room and their name is a trademark that is not translated, so they have
 * neither a proto field nor a `SettingsEntry` — see the note on [storeDescriptionRes].
 */
@Composable
private fun SettingsSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private data class ChoiceOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/**
 * The install permission, read from the system rather than from us.
 *
 * The state is **re-read on every return to the foreground**, with `LifecycleResumeEffect`: the user
 * leaves, flips the switch in the Android settings and comes back, and the row must say the new
 * thing. The app detail screen does the same read for the same reason.
 *
 * It is not a setting and has no proto field: Android remembers that value, and keeping a copy would
 * be the same trap as store enablement — a second copy in the DataStore is a value that can diverge.
 *
 * And when the system screen **does not exist**, the row says so with the manufacturer's name. ROMs
 * that get in the way of `REQUEST_INSTALL_PACKAGES` are also the ones that may have moved that
 * screen, and a button that does nothing and does not say so sends people looking for the fault
 * inside the app.
 */
@Composable
private fun UnknownSourcesRow(filter: SettingsFilter, modifier: Modifier = Modifier) {
    if (!filter.shows(SettingsActionKey.ALLOW_UNKNOWN_SOURCES)) return
    val entry = actionOf(SettingsActionKey.ALLOW_UNKNOWN_SOURCES)
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    var granted by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    var unreachable by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = context.packageManager.canRequestPackageInstalls()
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            bottom = spacing.small,
        ),
    ) {
        Text(
            text = stringResource(entry.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = spacing.small),
        )
        Text(
            text = stringResource(
                if (granted) R.string.settings_unknown_sources_granted
                else R.string.settings_unknown_sources_denied,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.extraSmall),
        )
        Text(
            text = if (unreachable) {
                stringResource(R.string.settings_unknown_sources_unreachable, Build.MANUFACTURER)
            } else {
                stringResource(entry.descriptionRes)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.extraSmall),
        )
        TextButton(
            onClick = { unreachable = !InstallSources.open(context) },
            modifier = Modifier.padding(top = spacing.extraSmall),
        ) {
            Text(text = stringResource(entry.actionRes))
        }
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<ChoiceOption>,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { option ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option.selected,
                                role = Role.RadioButton,
                                onClick = {
                                    option.onSelect()
                                    onDismiss()
                                },
                            )
                            .padding(vertical = spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
                    ) {
                        // The Row is already selectable: the RadioButton must not be a second
                        // accessibility target for the same action.
                        RadioButton(selected = option.selected, onClick = null)
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_dialog_dismiss))
            }
        },
    )
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_option_system
    ThemeMode.LIGHT -> R.string.settings_theme_option_light
    ThemeMode.DARK -> R.string.settings_theme_option_dark
}

// --------------------------------------------------------------------------- preview

private val PreviewState = SettingsUiState.Ready(
    appearance = AppearanceSettings(themeMode = ThemeMode.SYSTEM, dynamicColor = true, languageTag = "it"),
    updates = UpdateSettings(),
    versions = VersionSettings(),
    installation = InstallSettings(),
    security = SecuritySettings(),
    network = NetworkSettings(),
    remoteConfig = RemoteConfigSettings(),
    search = SearchSettings(),
    notifications = NotificationSettings(),
    diagnostics = DiagnosticsSettings(),
)

@Preview(name = "Settings light")
@Composable
private fun SettingsScreenLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) {
        SettingsScreen(PreviewState, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Settings dark")
@Composable
private fun SettingsScreenDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) {
        SettingsScreen(PreviewState, {}, {}, {}, {}, {}, {})
    }
}
