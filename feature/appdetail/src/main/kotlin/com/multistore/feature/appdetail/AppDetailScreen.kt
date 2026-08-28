package com.multistore.feature.appdetail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multistore.core.common.result.AppError
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.ContainerProblem
import com.multistore.core.data.repository.CrossStoreAvailability
import com.multistore.core.data.repository.CrossStoreLookup
import com.multistore.core.data.repository.StoreAvailability
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.installer.verify.PreInstallVerifier.VerificationOutcome
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.AppVersion
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.VersionRef
import com.multistore.core.data.repository.VersionOffer
import com.multistore.core.ui.component.AppIcon
import com.multistore.core.ui.component.EmptyState
import com.multistore.core.ui.component.MultiStoreDetailTopAppBar
import com.multistore.core.ui.ExternalLinks
import com.multistore.core.ui.InstallSources
import com.multistore.core.ui.component.appErrorMessage
import com.multistore.core.ui.component.installFailureMessage
import com.multistore.core.ui.rememberPreferredLanguageTags
import com.multistore.store.api.DownloadHint

/**
 * An app's page on one store, and the button that installs it.
 *
 * Three things that do not show in the shape of the code but explain its structure:
 *
 *  1. **The confirmation intent is launched by this screen**, not by the repository. Since API 34
 *     the system confirmation activity cannot start from the background, so only something that
 *     knows it is in the foreground may launch it.
 *  2. **The install permission is re-checked on every return to the foreground.** The user leaves
 *     the app to grant it and comes back: without [LifecycleResumeEffect] the screen would keep
 *     asking them to do something they have just done.
 *  3. **Assisted downloads do not open the system browser.** `:feature:webviewdownload` handles
 *     them, and the difference is not cosmetic: with the system browser the APK lands in the
 *     Downloads folder and Android installs it, with none of the pipeline's checks having run.
 */
@Composable
fun AppDetailScreen(
    onBack: () -> Unit,
    onUserAssistedDownload: (UserAssistedRequest) -> Unit,
    onOpenListing: (StoreId, StoreAppRef) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var canInstallPackages by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    LifecycleResumeEffect(Unit) {
        canInstallPackages = context.packageManager.canRequestPackageInstalls()
        onPauseOrDispose { }
    }

    LaunchedUserActions(viewModel) { intent ->
        // FLAG_ACTIVITY_NEW_TASK: the intent comes from the system's `PendingIntent` and is
        // launched from a Context that may not be an Activity's.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // `true` when opening the permission screen was attempted and this device has none: the page
    // says so, because a button that does nothing and does not say so sends people looking for the
    // fault in the wrong place.
    var installSourcesUnreachable by remember { mutableStateOf(false) }

    // The notification permission is asked for **on the first real download**, not at the splash
    // screen: asked cold, "MultiStore wants to send you notifications" has no context; asked on
    // pressing Install, it explains itself.
    //
    // The outcome **gates nothing**: a foreground service does not require POST_NOTIFICATIONS, so
    // denying it leaves the download perfectly functional and merely invisible. That is why the
    // install proceeds in both branches of the callback.
    var notificationPermissionAsked by remember { mutableStateOf(false) }
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionAsked = true
        viewModel.install()
    }

    // The "open in browser" button exists only if the store publishes a page **and** something on
    // this device would open it. Absent rather than disabled: a greyed-out button makes people
    // wonder why, one that is not there promises nothing.
    val listingUrl = (uiState as? AppDetailUiState.Ready)?.detail?.listingUrl
    val openInBrowser = listingUrl
        ?.takeIf { ExternalLinks.canOpen(context, it) }
        ?.let { url -> { ExternalLinks.open(context, url); Unit } }

    AppDetailScreen(
        uiState = uiState,
        preferredLanguageTags = rememberPreferredLanguageTags(),
        canInstallPackages = canInstallPackages,
        installSourcesUnreachable = installSourcesUnreachable,
        onOpenInBrowser = openInBrowser,
        onBack = onBack,
        onInstall = {
            if (!notificationPermissionAsked && context.needsNotificationPermission()) {
                notificationPermissionAsked = true
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.install()
            }
        },
        onUninstall = viewModel::uninstall,
        onCancel = viewModel::cancel,
        onDismissOutcome = viewModel::dismissInstallOutcome,
        onGrantInstallPermission = {
            // If that screen does not exist on this device, the button says so instead of bringing
            // the app down. See `InstallSources`.
            if (!InstallSources.open(context)) {
                installSourcesUnreachable = true
            }
        },
        onInstallFromDownload = viewModel::installFromDownload,
        onUserAssistedDownload = onUserAssistedDownload,
        storeDisplayName = viewModel::storeDisplayName,
        onOpenListing = onOpenListing,
        onLookUpOtherStores = viewModel::lookUpOtherStores,
        onConfirmMatch = viewModel::confirmMatch,
        onRejectMatch = viewModel::rejectMatch,
        onToggleVersionHistory = viewModel::toggleVersionHistory,
        onShowVersionHistory = viewModel::showVersionHistory,
        onRetryVersionHistory = viewModel::retryVersionHistory,
        onInstallVersion = { version -> viewModel.install(explicitVersion = version) },
        modifier = modifier,
    )
}

@Composable
private fun LaunchedUserActions(viewModel: AppDetailViewModel, onIntent: (Intent) -> Unit) {
    LaunchedEffect(viewModel) {
        viewModel.userActions.collect(onIntent)
    }
}

/**
 * Everything needed to open the store page, in a single value.
 *
 * Five loose parameters in a lambda swap places without the compiler noticing — they are four
 * strings and an enum — and swapping `ref` and `versionRef` would download the wrong version with
 * no visible error.
 */
data class UserAssistedRequest(
    val storeId: StoreId,
    val ref: StoreAppRef,
    val versionRef: VersionRef,
    val pageUrl: String,
    val hint: DownloadHint,
)

/** ViewModel-free variant, for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDetailScreen(
    uiState: AppDetailUiState,
    preferredLanguageTags: List<String>,
    canInstallPackages: Boolean,
    installSourcesUnreachable: Boolean = false,
    /** `null` when nothing on this device would open that page: see `ExternalLinks`. */
    onOpenInBrowser: (() -> Unit)? = null,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
    onDismissOutcome: () -> Unit,
    onGrantInstallPermission: () -> Unit,
    onInstallFromDownload: (Long) -> Unit,
    onUserAssistedDownload: (UserAssistedRequest) -> Unit,
    storeDisplayName: (StoreId) -> String,
    onOpenListing: (StoreId, StoreAppRef) -> Unit,
    onLookUpOtherStores: () -> Unit,
    onConfirmMatch: (Long) -> Unit,
    onRejectMatch: (Long) -> Unit,
    onToggleVersionHistory: () -> Unit,
    onShowVersionHistory: () -> Unit,
    onRetryVersionHistory: () -> Unit,
    onInstallVersion: (AppVersion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (uiState as? AppDetailUiState.Ready)?.detail?.listing?.summary?.title
        ?: stringResource(R.string.appdetail_title)

    Scaffold(
        modifier = modifier,
        topBar = {
            MultiStoreDetailTopAppBar(
                title = title,
                onBack = onBack,
                actions = {
                    // Absent, not disabled: the button is there only if something would open it.
                    onOpenInBrowser?.let { open ->
                        IconButton(onClick = open) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription =
                                    stringResource(R.string.appdetail_action_open_in_browser),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState) {
                AppDetailUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                AppDetailUiState.NotFound -> EmptyState(
                    icon = Icons.Rounded.Warning,
                    title = stringResource(R.string.appdetail_not_found_title),
                    description = stringResource(R.string.appdetail_not_found_message),
                )

                is AppDetailUiState.Ready -> ReadyContent(
                    state = uiState,
                    preferredLanguageTags = preferredLanguageTags,
                    canInstallPackages = canInstallPackages,
                    installSourcesUnreachable = installSourcesUnreachable,
                    onInstall = onInstall,
                    onUninstall = onUninstall,
                    onCancel = onCancel,
                    onDismissOutcome = onDismissOutcome,
                    onGrantInstallPermission = onGrantInstallPermission,
                    onInstallFromDownload = onInstallFromDownload,
                    onUserAssistedDownload = onUserAssistedDownload,
                    storeDisplayName = storeDisplayName,
                    onOpenListing = onOpenListing,
                    onLookUpOtherStores = onLookUpOtherStores,
                    onConfirmMatch = onConfirmMatch,
                    onRejectMatch = onRejectMatch,
                    onToggleVersionHistory = onToggleVersionHistory,
                    onShowVersionHistory = onShowVersionHistory,
                    onRetryVersionHistory = onRetryVersionHistory,
                    onInstallVersion = onInstallVersion,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: AppDetailUiState.Ready,
    preferredLanguageTags: List<String>,
    canInstallPackages: Boolean,
    installSourcesUnreachable: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
    onDismissOutcome: () -> Unit,
    onGrantInstallPermission: () -> Unit,
    onInstallFromDownload: (Long) -> Unit,
    onUserAssistedDownload: (UserAssistedRequest) -> Unit,
    storeDisplayName: (StoreId) -> String,
    onOpenListing: (StoreId, StoreAppRef) -> Unit,
    onLookUpOtherStores: () -> Unit,
    onConfirmMatch: (Long) -> Unit,
    onRejectMatch: (Long) -> Unit,
    onToggleVersionHistory: () -> Unit,
    onShowVersionHistory: () -> Unit,
    onRetryVersionHistory: () -> Unit,
    onInstallVersion: (AppVersion) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = spacing.huge),
    ) {
        Header(
            state = state,
            preferredLanguageTags = preferredLanguageTags,
            storeDisplayName = storeDisplayName,
        )

        if (!canInstallPackages) {
            NoticeCard(
                title = stringResource(R.string.appdetail_unknown_sources_title),
                message = if (installSourcesUnreachable) {
                    stringResource(R.string.appdetail_unknown_sources_unreachable, Build.MANUFACTURER)
                } else {
                    stringResource(R.string.appdetail_unknown_sources_message)
                },
                actionLabel = stringResource(R.string.appdetail_unknown_sources_action),
                onAction = onGrantInstallPermission,
            )
        }

        ActionArea(
            state = state,
            onInstall = onInstall,
            onUninstall = onUninstall,
            onShowVersionHistory = onShowVersionHistory,
            onCancel = onCancel,
            onDismissOutcome = onDismissOutcome,
            onInstallFromDownload = onInstallFromDownload,
            onUserAssistedDownload = onUserAssistedDownload,
        )

        VerificationCard(outcome = state.verification)

        CrossStoreSection(
            state = state,
            storeDisplayName = storeDisplayName,
            onOpenListing = onOpenListing,
            onLookUpOtherStores = onLookUpOtherStores,
        )
        PossibleMatchesSection(
            state = state,
            storeDisplayName = storeDisplayName,
            onOpenListing = onOpenListing,
            onConfirmMatch = onConfirmMatch,
            onRejectMatch = onRejectMatch,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        AntiFeatures(state = state, preferredLanguageTags = preferredLanguageTags)
        Description(state = state, preferredLanguageTags = preferredLanguageTags)
        VersionFacts(state = state)
        VersionHistorySection(
            state = state,
            onToggle = onToggleVersionHistory,
            onRetry = onRetryVersionHistory,
            onInstallVersion = onInstallVersion,
        )
    }
}

/**
 * Who publishes this app, from where, **and the three numbers that decide whether scrolling is
 * worth it**.
 *
 * Version, size and rating used to be further down, in a section between the description and the
 * previous versions. They are the three things looked at first — "is it the latest? how big is it?
 * how do people rate it?" — and asking somebody who has just opened the page to scroll for them
 * meant a scroll to answer a question that comes before reading.
 */
@Composable
private fun Header(
    state: AppDetailUiState.Ready,
    preferredLanguageTags: List<String>,
    storeDisplayName: (StoreId) -> String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val summary = state.detail.listing.summary
    Row(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.small,
            bottom = spacing.large,
        ),
    ) {
        AppIcon(iconUrl = summary.iconUrl, size = HEADER_ICON_SIZE)
        Column(modifier = Modifier.padding(start = spacing.large)) {
            summary.developer?.let { developer ->
                Text(
                    text = developer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.appdetail_from_store, state.storeName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
            summary.packageName?.let { packageName ->
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HeaderFacts(state = state, modifier = Modifier.padding(top = spacing.small))
            NewerElsewhereNote(state = state, storeDisplayName = storeDisplayName)
            summary.summary.resolve(preferredLanguageTags)?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = spacing.medium),
                )
            }
            state.detail.installed?.let { installed ->
                Text(
                    text = stringResource(
                        R.string.appdetail_installed_version,
                        installed.versionName ?: installed.versionCode.toString(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = spacing.small),
                )
            }
            if (state.detail.stale) {
                Text(
                    text = stringResource(R.string.appdetail_stale_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.extraSmall),
                )
            }
        }
    }
}

/**
 * Version, size and rating on a single line, separated by a dot.
 *
 * One `Text` and not three: they are three facts about the same thing, and three lines would make
 * them read as three independent statements. The separator is a character with no letters, which
 * the hardcoded-string detector deliberately ignores.
 *
 * **The version is the one the button would install**, not "the latest the store names": on F-Droid
 * the two differ on 14 packages out of 4,257, and showing the second would state a different number
 * from the one about to be downloaded. Where there is nothing to offer it falls back to what the
 * listing declares, because even a listing with no installable artifact has a version number to show.
 *
 * The rating is **missing on six stores out of nine** and the line does not fake it: only what
 * somebody published gets written.
 */
@Composable
private fun HeaderFacts(state: AppDetailUiState.Ready, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val summary = state.detail.listing.summary
    val offered = state.detail.selection.versionOrNull()
    val parts = listOfNotNull(
        summary.rating?.let { stringResource(R.string.appdetail_meta_rating, it) },
        offered?.versionName ?: summary.latestVersionName,
        offered?.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
    )
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(separator = FACT_SEPARATOR),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * "apkmirror has 154.0": the comparison with the other stores, when it can be demonstrated.
 *
 * The conditions that make it demonstrable — a certain match, two `versionCode`s, a `versionName` to
 * read — live in `CrossStoreAvailability.newerThan`, together with the measurement that explains why
 * the Play Store version is not here instead.
 */
@Composable
private fun NewerElsewhereNote(
    state: AppDetailUiState.Ready,
    storeDisplayName: (StoreId) -> String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val newer = state.crossStore.newerThan(
        state.detail.selection.versionOrNull()?.versionCode,
    ) ?: return
    Text(
        text = stringResource(
            R.string.appdetail_newer_elsewhere,
            storeDisplayName(newer.storeId),
            newer.versionName,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.padding(top = spacing.extraSmall),
    )
}

/**
 * The button, and what it says while it cannot be pressed.
 *
 * The order of the branches is not arbitrary: **install state first, version-selection rule second**.
 * An installation refused by verification must stay visible even if `selection` keeps saying "offer
 * 1.2.3" — otherwise the message explaining *why* it was refused would vanish the instant it needs
 * reading.
 */
@Composable
private fun ActionArea(
    state: AppDetailUiState.Ready,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onShowVersionHistory: () -> Unit,
    onCancel: () -> Unit,
    onDismissOutcome: () -> Unit,
    onInstallFromDownload: (Long) -> Unit,
    onUserAssistedDownload: (UserAssistedRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            bottom = spacing.large,
        ),
    ) {
        when (val install = state.install) {
            InstallUiState.Idle -> IdleActions(
                state = state,
                onInstall = onInstall,
                onUninstall = onUninstall,
                onShowVersionHistory = onShowVersionHistory,
            )

            is InstallUiState.Downloading -> Progress(
                label = stringResource(R.string.appdetail_state_downloading),
                fraction = install.fraction,
                detail = byteProgress(install.bytesDownloaded, install.bytesTotal),
                onCancel = onCancel,
            )

            is InstallUiState.Writing -> Progress(
                label = stringResource(R.string.appdetail_state_writing),
                fraction = (install.bytesWritten.toFloat() / install.bytesTotal.coerceAtLeast(1))
                    .coerceIn(0f, 1f),
                detail = byteProgress(install.bytesWritten, install.bytesTotal),
                onCancel = null,
            )

            InstallUiState.Resolving -> Progress(
                label = stringResource(R.string.appdetail_state_resolving),
                fraction = null,
                detail = null,
                onCancel = onCancel,
            )

            InstallUiState.Verifying -> Progress(
                label = stringResource(R.string.appdetail_state_verifying),
                fraction = null,
                detail = null,
                onCancel = null,
            )

            // The detail is not decoration: it is the only place the user can see why, of 238 MB
            // downloaded, 180 get installed.
            is InstallUiState.Unpacking -> Progress(
                label = stringResource(R.string.appdetail_state_unpacking),
                fraction = null,
                detail = pluralStringResource(
                    R.plurals.appdetail_unpacking_detail,
                    install.summary.install.size,
                    install.summary.install.size,
                    Formatter.formatShortFileSize(LocalContext.current, install.summary.installBytes),
                ),
                onCancel = null,
            )

            is InstallUiState.PlacingExpansions -> Progress(
                label = stringResource(R.string.appdetail_state_placing_expansions),
                fraction = null,
                detail = Formatter.formatShortFileSize(LocalContext.current, install.bytes),
                onCancel = null,
            )

            InstallUiState.Committing -> Progress(
                label = stringResource(R.string.appdetail_state_committing),
                fraction = null,
                detail = null,
                onCancel = null,
            )

            InstallUiState.AwaitingUserAction -> Progress(
                label = stringResource(R.string.appdetail_state_awaiting_user),
                fraction = null,
                detail = null,
                onCancel = null,
            )

            InstallUiState.Uninstalling -> Progress(
                label = stringResource(R.string.appdetail_state_uninstalling),
                fraction = null,
                detail = null,
                onCancel = null,
            )

            InstallUiState.Installed -> Outcome(
                title = stringResource(R.string.appdetail_state_installed),
                message = null,
                onDismiss = onDismissOutcome,
            )

            InstallUiState.Uninstalled -> Outcome(
                title = stringResource(R.string.appdetail_state_uninstalled),
                message = null,
                onDismiss = onDismissOutcome,
            )

            InstallUiState.Cancelled -> Outcome(
                title = stringResource(R.string.appdetail_state_cancelled),
                message = null,
                onDismiss = onDismissOutcome,
            )

            InstallUiState.Incompatible -> Outcome(
                title = stringResource(R.string.appdetail_incompatible_title),
                message = stringResource(R.string.appdetail_incompatible_message),
                onDismiss = onDismissOutcome,
                emphasis = Emphasis.WARNING,
            )

            is InstallUiState.Failed -> Outcome(
                title = stringResource(R.string.appdetail_failed_title),
                // `installFailureMessage` and not `appErrorMessage`: the `PackageInstaller` code
                // distinguishes seven outcomes leading to seven different actions, and the generic
                // sentence flattened them all. See `InstallFailure`.
                message = (install.error as? AppError.InstallFailed)
                    ?.let { installFailureMessage(it) }
                    ?: listOfNotNull(appErrorMessage(install.error), install.systemMessage)
                        .joinToString(separator = "\n"),
                onDismiss = onDismissOutcome,
                emphasis = Emphasis.ERROR,
            )

            is InstallUiState.Rejected -> Outcome(
                title = stringResource(R.string.appdetail_rejected_title),
                message = verificationMessage(install.outcome),
                onDismiss = onDismissOutcome,
                emphasis = Emphasis.ERROR,
            )

            is InstallUiState.ContainerRejected -> Outcome(
                title = stringResource(R.string.appdetail_container_rejected_title),
                message = containerMessage(install.problem),
                onDismiss = onDismissOutcome,
                emphasis = Emphasis.ERROR,
            )

            is InstallUiState.SignerConflict -> SignerConflictCard(onUninstall = onUninstall)

            is InstallUiState.UserAssisted -> NoticeCard(
                title = stringResource(R.string.appdetail_user_assisted_title),
                message = stringResource(R.string.appdetail_user_assisted_message),
                actionLabel = stringResource(R.string.appdetail_user_assisted_action),
                onAction = {
                    onUserAssistedDownload(
                        UserAssistedRequest(
                            storeId = state.detail.listing.storeId,
                            ref = state.detail.listing.ref,
                            versionRef = install.versionRef,
                            pageUrl = install.pageUrl,
                            hint = install.hint,
                        ),
                    )
                },
            )

            is InstallUiState.ReadyToInstall -> NoticeCard(
                title = stringResource(R.string.appdetail_ready_to_install_title),
                message = stringResource(R.string.appdetail_ready_to_install_message),
                actionLabel = stringResource(R.string.appdetail_action_install),
                onAction = { onInstallFromDownload(install.downloadId) },
            )
        }
    }
}

@Composable
private fun IdleActions(
    state: AppDetailUiState.Ready,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onShowVersionHistory: () -> Unit,
) {
    val spacing = LocalSpacing.current
    when (val selection = state.detail.selection) {
        is VersionSelection.Outcome.Offer -> Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selection.isUpdate) {
                        stringResource(R.string.appdetail_action_update)
                    } else {
                        stringResource(R.string.appdetail_action_install)
                    },
                )
            }
            if (state.detail.installed != null) {
                OutlinedButton(onClick = onUninstall) {
                    Text(text = stringResource(R.string.appdetail_action_uninstall))
                }
            }
        }

        is VersionSelection.Outcome.UpToDate -> Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                // "Up to date" and "cannot be known" are two sentences, not one. uptodown does not
                // publish the versionCode anywhere on the site: without the distinction, every app
                // taken from there would claim to be up to date forever, with the same confidence as
                // one that really is.
                text = if (selection.comparable) {
                    stringResource(R.string.appdetail_state_up_to_date)
                } else {
                    stringResource(R.string.appdetail_state_update_unknown)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onUninstall) {
                Text(text = stringResource(R.string.appdetail_action_uninstall))
            }
        }

        is VersionSelection.Outcome.SignerConflict -> SignerConflictCard(onUninstall = onUninstall)

        VersionSelection.Outcome.Incompatible -> UnavailableNote(
            title = stringResource(R.string.appdetail_incompatible_title),
            message = stringResource(R.string.appdetail_incompatible_message),
        )

        // A page discovered through cross-store matching starts from a **result list**, therefore
        // with no versions: while it is still being re-read from the store, "this store publishes no
        // installable package" would be a false sentence said with confidence. It applies to the
        // usual stale-while-revalidate too.
        VersionSelection.Outcome.NothingInstallable -> if (state.refreshing) {
            Progress(
                label = stringResource(R.string.appdetail_state_reading_store),
                fraction = null,
                detail = null,
                onCancel = null,
            )
        } else {
            UnavailableNote(
                title = stringResource(R.string.appdetail_nothing_installable_title),
                message = stringResource(R.string.appdetail_nothing_installable_message),
            )
        }

        // Deliberately distinct from `NothingInstallable`: "this store has no package" and "it only
        // exists as a beta" are two different dead ends, and the second one has a name worth saying.
        // The button opens the history rather than installing: that is where those versions are, one
        // per row and each with its channel label. An "install it anyway" that picked *which* beta on
        // its own would decide for the user exactly the thing the Settings switch exists not to
        // decide by itself.
        is VersionSelection.Outcome.OnlyOtherChannels -> UnavailableNote(
            title = stringResource(R.string.appdetail_only_other_channels_title),
            message = stringResource(
                R.string.appdetail_only_other_channels_message,
                selection.channels.sorted().joinToString(", "),
            ),
            actionLabel = stringResource(R.string.appdetail_only_other_channels_action),
            onAction = onShowVersionHistory,
        )

        // The pin was set by the user, so there is nothing to repair here: there is something to
        // **say about what is being held back**. Without the name of the withheld version the note
        // would be a warning with no content, and the button — when present — stays because a pin
        // means "no further", not "nothing".
        is VersionSelection.Outcome.Pinned -> Column(modifier = Modifier.fillMaxWidth()) {
            UnavailableNote(
                title = stringResource(
                    R.string.appdetail_pinned_title,
                    selection.pinnedVersionCode,
                ),
                message = stringResource(
                    R.string.appdetail_pinned_message,
                    selection.heldBack.versionName,
                ),
            )
            val offer = selection.offer
            if (offer != null) {
                Button(
                    onClick = onInstall,
                    modifier = Modifier.padding(top = spacing.small),
                ) {
                    Text(
                        text = if (offer.isUpdate) {
                            stringResource(R.string.appdetail_action_update)
                        } else {
                            stringResource(R.string.appdetail_action_install)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Progress(
    label: String,
    fraction: Float?,
    detail: String?,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    EmphasisCard(emphasis = Emphasis.NEUTRAL, modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.medium),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.medium),
            )
        }
        onCancel?.let {
            CardActions {
                // Cancelling a download is a **destructive and optional** gesture: outlined rather
                // than filled, because a filled button here would invite pressing the thing that
                // throws away the megabytes already fetched. It is the only place on this page where
                // the offered gesture is not the one you want.
                OutlinedButton(onClick = it) {
                    Text(text = stringResource(R.string.appdetail_action_cancel))
                }
            }
        }
    }
}

/**
 * How serious what the card says is: it decides colour and icon, not shape.
 *
 * Three values rather than a `Boolean`, because the situations are three and lead to three different
 * reactions: "it worked" (neutral), "it can be done but it costs something" (warning), "it did not
 * work" (error). Merging two of them would make a signer conflict — which has a way out — read with
 * the same face as a hash mismatch, which has none.
 */
private enum class Emphasis { NEUTRAL, WARNING, ERROR }

@Composable
private fun Emphasis.container() = when (this) {
    Emphasis.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh
    Emphasis.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
    Emphasis.ERROR -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun Emphasis.onContainer() = when (this) {
    Emphasis.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    Emphasis.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
    Emphasis.ERROR -> MaterialTheme.colorScheme.onErrorContainer
}

private fun Emphasis.icon() = when (this) {
    Emphasis.NEUTRAL -> Icons.Rounded.Info
    Emphasis.WARNING -> Icons.Rounded.Warning
    Emphasis.ERROR -> Icons.Rounded.Warning
}

/**
 * The button on a coloured card cannot use the base theme's colours.
 *
 * A plain `Button` is `primary` on `onPrimary`, and inside an error container that block ends up
 * arguing with the background instead of standing out on it. The colours here are the ones Material
 * calls "on a container": the fill stays a fill, but it belongs to the card's family. It is also the
 * only way of keeping the theming rule — no fixed colours, everything from `colorScheme` — without
 * giving up the solid button.
 */
@Composable
private fun Emphasis.buttonColors() = when (this) {
    Emphasis.NEUTRAL -> ButtonDefaults.buttonColors()
    Emphasis.WARNING -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    )
    Emphasis.ERROR -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    )
}

/**
 * How it went: installed, cancelled, refused.
 *
 * Same shape as the warnings, and that is not laziness: an outcome **is** a warning, except that the
 * gesture it offers is "I have read it". The button stays filled because closing this card is what
 * puts the page back into a state where one can try again — on a failure it is the only thing to do,
 * and a faded link made it look optional.
 */
@Composable
private fun Outcome(
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Emphasis = Emphasis.NEUTRAL,
) {
    EmphasisCard(emphasis = emphasis, modifier = modifier) {
        NoticeBody(title = title, message = message, emphasis = emphasis)
        CardActions {
            Button(onClick = onDismiss, colors = emphasis.buttonColors()) {
                Text(text = stringResource(R.string.appdetail_action_dismiss))
            }
        }
    }
}

/**
 * "Also available on", and the button that asks for it.
 *
 * The three sources sit behind the repository; only the outcome is visible here. What the screen has
 * to make obvious is that **changing store changes what verification will be able to prove**: some
 * stores publish a hash and others do not. That is why the row carries the store's name rather than
 * a generic icon.
 */
@Composable
private fun CrossStoreSection(
    state: AppDetailUiState.Ready,
    storeDisplayName: (StoreId) -> String,
    onOpenListing: (StoreId, StoreAppRef) -> Unit,
    onLookUpOtherStores: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val availability = state.crossStore
    val others = availability.availableOn
    if (others.isEmpty() && !availability.canLookUp &&
        availability.lookup != CrossStoreLookup.RUNNING
    ) {
        return
    }

    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            bottom = spacing.large,
        ),
    ) {
        Text(
            text = if (others.isEmpty()) {
                stringResource(R.string.appdetail_stores_title)
            } else {
                // The count includes the store being looked at: "available on 3 stores" when the
                // others are two. It is the number the user expects, and the same one the search row
                // showed a moment earlier.
                pluralStringResource(
                    R.plurals.appdetail_stores_available_count,
                    others.size + 1,
                    others.size + 1,
                )
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        others.forEach { entry ->
            StoreRow(
                storeName = storeDisplayName(entry.storeId),
                title = entry.listing.summary.title,
                onClick = { onOpenListing(entry.storeId, entry.ref) },
                modifier = Modifier.padding(top = spacing.small),
            )
        }

        when {
            availability.lookup == CrossStoreLookup.RUNNING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = spacing.small),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(spacing.large))
                Text(
                    text = stringResource(R.string.appdetail_stores_lookup_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = spacing.small),
                )
            }

            availability.canLookUp -> Column {
                if (others.isEmpty()) {
                    Text(
                        text = stringResource(R.string.appdetail_stores_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.extraSmall),
                    )
                }
                // Outlined rather than filled: it is a gesture that costs **network requests to eight
                // third-party sites**, and this screen's fill belongs to installation.
                OutlinedButton(
                    onClick = onLookUpOtherStores,
                    modifier = Modifier.padding(top = spacing.small),
                ) {
                    Text(text = stringResource(R.string.appdetail_stores_lookup_action))
                }
            }
        }
    }
}

/**
 * "This might be the same app", with two buttons and no shortcut.
 *
 * Below 0.85 confidence nothing is **ever merged silently**, because a wrong match means offering
 * another app's APK. This section is where that rule becomes visible: the listings stay separate, the
 * app says why, and a person decides.
 *
 * Confirm and reject appear only where there is a `listingId`, that is where the listing is already a
 * `store_listings` row: without one there would be nothing to record the choice against, and a button
 * that leaves no trace is worse than no button.
 */
@Composable
private fun PossibleMatchesSection(
    state: AppDetailUiState.Ready,
    storeDisplayName: (StoreId) -> String,
    onOpenListing: (StoreId, StoreAppRef) -> Unit,
    onConfirmMatch: (Long) -> Unit,
    onRejectMatch: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidates = state.crossStore.possibleMatches
    if (candidates.isEmpty()) return
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            bottom = spacing.large,
        ),
    ) {
        Text(
            text = stringResource(R.string.appdetail_possible_match_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.appdetail_possible_match_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.extraSmall),
        )

        candidates.forEach { candidate ->
            Column(modifier = Modifier.padding(top = spacing.small)) {
                StoreRow(
                    storeName = storeDisplayName(candidate.storeId),
                    title = candidate.listing.summary.title,
                    onClick = { onOpenListing(candidate.storeId, candidate.ref) },
                )
                candidate.listingId?.let { listingId ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.small, Alignment.End),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.small),
                    ) {
                        // "They are different" outlined and "it is the same" filled, and that is not
                        // missing symmetry: the two choices do not cost the same. Rejecting leaves
                        // things as they are, confirming **writes** to `identity_overrides` and from
                        // then on the two listings are the same app — a match that is meant to be
                        // impossible by construction below the threshold is here being authorised by
                        // a person, and it has to be drawn as a decision.
                        OutlinedButton(onClick = { onRejectMatch(listingId) }) {
                            Text(text = stringResource(R.string.appdetail_possible_match_reject))
                        }
                        Button(onClick = { onConfirmMatch(listingId) }) {
                            Text(text = stringResource(R.string.appdetail_possible_match_confirm))
                        }
                    }
                }
            }
        }
    }
}

/** A row reading "store name — the title that store gives it". */
@Composable
private fun StoreRow(
    storeName: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = storeName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                // The title as **that** store writes it, not the one being looked at: it is the only
                // way for the user to recognise what they are about to open, and on whoever
                // redistributes modified builds the difference is the whole point.
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SignerConflictCard(onUninstall: () -> Unit, modifier: Modifier = Modifier) {
    NoticeCard(
        title = stringResource(R.string.appdetail_signer_conflict_title),
        message = stringResource(R.string.appdetail_signer_conflict_message),
        actionLabel = stringResource(R.string.appdetail_signer_conflict_action),
        onAction = onUninstall,
        emphasis = Emphasis.WARNING,
        modifier = modifier,
    )
}

/**
 * A warning card: icon, title, explanation, and **the gesture that resolves it**.
 *
 * These blocks used to be flat `Surface`s with a `TextButton` at the bottom left, that is an action
 * that reads like a link. They are the points where the page asks the user to **do** something —
 * grant a permission, uninstall in order to update, open the store page — and in all three the
 * gesture is the only way out: drawing it as the least important element was the opposite of what it
 * means.
 *
 * The button is right-aligned because that is where the concluding gesture belongs, and it is the one
 * Material convention users recognise without reading.
 *
 * The icon is not decoration: it is what tells a warning that wants attention from one that merely
 * informs at a glance, and in themes where the three containers look alike it is the only signal
 * left. It is not announced to TalkBack — the title next to it already says the same thing in words.
 */
@Composable
private fun NoticeCard(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Emphasis = Emphasis.NEUTRAL,
) {
    EmphasisCard(emphasis = emphasis, modifier = modifier) {
        NoticeBody(title = title, message = message, emphasis = emphasis)
        CardActions {
            Button(
                onClick = onAction,
                colors = emphasis.buttonColors(),
            ) { Text(text = actionLabel) }
        }
    }
}

/**
 * The common shell of the warnings: colour, shape, spacing.
 *
 * A `Card` and not a `Surface`, for a reason that is not stylistic: `Card` brings the elevation and
 * the shape Material gives a block **detached from the flow**, which is exactly what these are — the
 * page scrolls, the warning does not.
 */
@Composable
private fun EmphasisCard(
    emphasis: Emphasis,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = LocalSpacing.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = emphasis.container(),
            contentColor = emphasis.onContainer(),
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.small),
    ) {
        Column(modifier = Modifier.padding(spacing.large), content = content)
    }
}

/** Icon and title on the same row, explanation underneath: the shape of every warning. */
@Composable
private fun ColumnScope.NoticeBody(title: String, message: String?, emphasis: Emphasis) {
    val spacing = LocalSpacing.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = emphasis.icon(),
            contentDescription = null,
            modifier = Modifier.size(spacing.large),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = spacing.small),
        )
    }
    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = spacing.small),
        )
    }
}

/** A card's buttons: at the bottom, to the right, spaced. */
@Composable
private fun ColumnScope.CardActions(content: @Composable RowScope.() -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.small, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.medium),
        content = content,
    )
}

/**
 * What the pipeline was **actually** able to verify about the file just installed.
 *
 * Three rows and no ceremony, but the reason it exists is not cosmetic. The pipeline has always
 * distinguished "verified" from "not contradicted" — `Ok.packageNameWasVerified`, `hashWasVerified`
 * and `signerWasVerified` have existed from the start and are covered by tests — only nobody read
 * them, and the distinction died there. Where the store does not publish the packageName, and that
 * is **4 out of 9**, the UI has to say "not contradicted", not "verified".
 *
 * There is also a promise to keep: the description of the "Install without checksum verification"
 * setting — already translated into five languages and already on screen — says that in that case
 * "the verification card says hash not verified". This is that card.
 *
 * The signer fingerprint is shown shortened: in full it is 64 hex characters nobody reads, and the
 * first eight pairs are enough to compare it with the one the developer published — which is the only
 * use it gets by hand.
 */
@Composable
private fun VerificationCard(
    outcome: VerificationOutcome.Ok?,
    modifier: Modifier = Modifier,
) {
    if (outcome == null) return
    val spacing = LocalSpacing.current
    // In the same frame as the warnings, and with no buttons: there is nothing to do here, there is
    // something to read. It is the only card on the page that merely informs, and it is right that it
    // stands out by the absence of a gesture rather than by a different outline.
    Column(
        modifier = modifier.padding(horizontal = spacing.screenHorizontal),
    ) {
        EmphasisCard(emphasis = Emphasis.NEUTRAL) {
        Text(
            text = stringResource(R.string.appdetail_verification_title),
            style = MaterialTheme.typography.titleSmall,
        )
        CheckRow(
            verified = outcome.packageNameWasVerified,
            okRes = R.string.appdetail_verification_package_ok,
            uncheckedRes = R.string.appdetail_verification_package_unchecked,
        )
        CheckRow(
            verified = outcome.hashWasVerified,
            okRes = R.string.appdetail_verification_hash_ok,
            uncheckedRes = R.string.appdetail_verification_hash_unchecked,
        )
        CheckRow(
            verified = outcome.signerWasVerified,
            okRes = R.string.appdetail_verification_signer_ok,
            uncheckedRes = R.string.appdetail_verification_signer_unchecked,
        )
        outcome.info.signerSha256.firstOrNull()?.let { signer ->
            Text(
                text = stringResource(
                    R.string.appdetail_verification_signer_fingerprint,
                    signer.hex.take(SIGNER_FINGERPRINT_CHARS).chunked(4).joinToString(" ").uppercase(),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
        }
        }
    }
}

@Composable
private fun CheckRow(
    verified: Boolean,
    @StringRes okRes: Int,
    @StringRes uncheckedRes: Int,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (verified) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
            // The row's text already states the outcome: repeating it in the icon would make
            // TalkBack users hear the same thing twice.
            contentDescription = null,
            tint = if (verified) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(CHECK_ICON_SIZE),
        )
        Text(
            text = stringResource(if (verified) okRes else uncheckedRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = spacing.small),
        )
    }
}

@Composable
private fun UnavailableNote(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    EmphasisCard(emphasis = Emphasis.NEUTRAL, modifier = modifier) {
        NoticeBody(title = title, message = message, emphasis = Emphasis.NEUTRAL)
        // Optional because four of these notices have nothing to offer: "does not run on this
        // device" is a dead end, and a button there would be a promise.
        if (actionLabel != null && onAction != null) {
            CardActions {
                Button(onClick = onAction) { Text(text = actionLabel) }
            }
        }
    }
}

/**
 * The versions this store publishes, and what can be done with each.
 *
 * It is collapsed by default because opening it is what authorises the request: on apkcombo, apkmody
 * and modyolo the history is a page of its own, and fetching it for every page opened would be the
 * speculative prefetching this project forbids. On the other six it costs nothing, but the section
 * behaves the same way: an interface that changes shape depending on the store is one the user never
 * learns.
 *
 * It does not appear at all when the store does not declare [StoreCapabilities.versionHistory] **and**
 * the catalogue holds a single version — that is, on an1, which publishes one listing and one file.
 * The second half of the condition covers the opposite case, a store that does not declare the
 * capability but whose listing carries more than one file anyway: hiding those would be hiding data
 * we have.
 *
 * What tells an installable row from one that is not is `VersionSelection.installability`, which lives
 * next to the selection rule because it is made of the same comparisons. The surprising row is "older
 * than the installed one": Android does not replace an app with an earlier version, and neither
 * installer passes a downgrade flag. Showing them as installable would mean a whole download and a
 * refusal from the system at the end.
 */
@Composable
internal fun VersionHistorySection(
    state: AppDetailUiState.Ready,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onInstallVersion: (AppVersion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val versions = state.detail.versions
    if (!state.versionHistorySupported && versions.size <= 1) return

    val spacing = LocalSpacing.current
    val history = state.versionHistory

    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.large,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onToggle)
                .heightIn(min = MIN_TOUCH_TARGET),
        ) {
            Text(
                text = stringResource(R.string.appdetail_versions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (history.expanded) {
                    Icons.Rounded.ExpandLess
                } else {
                    Icons.Rounded.ExpandMore
                },
                // Deliberately decorative: whoever reads with TalkBack hears the row's text and that
                // the row activates, which is all that is needed. A second label on the icon would
                // make it announced twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!history.expanded) return@Column

        if (history.loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = spacing.small),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(spacing.large))
                Text(
                    text = stringResource(R.string.appdetail_versions_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = spacing.small),
                )
            }
        }

        // A failure does not empty the section: what the catalogue has stays on screen, and this row
        // says that **the rest** did not arrive. Hiding it would give a short history that looks
        // complete.
        if (history.failed) {
            Text(
                text = stringResource(R.string.appdetail_versions_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.small),
            )
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = spacing.small),
            ) {
                Text(text = stringResource(R.string.appdetail_versions_retry))
            }
        }

        if (versions.size <= 1 && !history.loading && !history.failed) {
            Text(
                text = stringResource(R.string.appdetail_versions_only_current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.small),
            )
        }

        // Already ordered newest first, and already carrying their verdict: `versionOffers` in
        // `:core:data` does that, since it is the one holding the device profile.
        versions.forEach { offer ->
            VersionHistoryRow(
                offer = offer,
                enabled = !state.install.isBusy,
                onInstall = { onInstallVersion(offer.version) },
                modifier = Modifier.padding(top = spacing.medium),
            )
        }
    }
}

@Composable
private fun VersionHistoryRow(
    offer: VersionOffer,
    enabled: Boolean,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val version = offer.version

    // Size, date and channel on a single row: they are the three facts that tell two versions apart,
    // and each is missing on some store — none of the nine publishes all three.
    val facts = listOfNotNull(
        version.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
        version.publishedAt?.let {
            DateUtils.formatDateTime(
                context,
                it.toEpochMilliseconds(),
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
            )
        },
        // The channel names are written by the store — `Beta`, `Alpha` — and are shown as it writes
        // them: the same choice already made on the "preview only" notice.
        version.releaseChannels.takeIf { it.isNotEmpty() }?.joinToString(),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = version.versionName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (facts.isNotEmpty()) {
                    Text(
                        text = facts.joinToString(FACT_SEPARATOR),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (offer.installability == VersionSelection.Installability.INSTALLABLE) {
                // Tonal rather than filled: these are list rows, and one fill per row would compete
                // with the button at the top — which offers the version the rule **chose**, whereas
                // these are the ones the user can choose themselves.
                FilledTonalButton(onClick = onInstall, enabled = enabled) {
                    Text(text = stringResource(R.string.appdetail_action_install))
                }
            }
        }
        offer.installability.noteRes()?.let { note ->
            Text(
                text = stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
        }
    }
}

/**
 * The sentence under a version that cannot be installed, or `null` for the one that can.
 *
 * Four sentences and not one, for the same reason as the nine outcomes of [verificationMessage] and
 * the seven of `installFailureExplanation`: they lead to four different things to do. "Older" is
 * resolved by uninstalling the app, "does not run on this device" is not resolved at all, and
 * "installed" is not a problem.
 */
private fun VersionSelection.Installability.noteRes(): Int? = when (this) {
    VersionSelection.Installability.INSTALLABLE -> null
    VersionSelection.Installability.INSTALLED -> R.string.appdetail_versions_state_installed
    VersionSelection.Installability.OLDER_THAN_INSTALLED -> R.string.appdetail_versions_state_older
    VersionSelection.Installability.INCOMPATIBLE -> R.string.appdetail_versions_state_incompatible
    VersionSelection.Installability.UNSUPPORTED_ARTIFACT ->
        R.string.appdetail_versions_state_unsupported
}

@Composable
private fun AntiFeatures(
    state: AppDetailUiState.Ready,
    preferredLanguageTags: List<String>,
    modifier: Modifier = Modifier,
) {
    val version = state.detail.selection.versionOrNull() ?: state.detail.listing.versions.firstOrNull()
    val antiFeatures = version?.antiFeatures.orEmpty()
    if (antiFeatures.isEmpty()) return

    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.large,
        ),
    ) {
        Text(
            text = stringResource(R.string.appdetail_section_antifeatures),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        antiFeatures.forEach { antiFeature ->
            // The localised name comes from the store, not from strings.xml: it is network data, and
            // stays correct even when F-Droid publishes one we do not know.
            val resolved = state.taxonomy.antiFeature(antiFeature.id) ?: antiFeature
            Text(
                text = resolved.displayName(preferredLanguageTags),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = spacing.small),
            )
            resolved.description.resolve(preferredLanguageTags)?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Description(
    state: AppDetailUiState.Ready,
    preferredLanguageTags: List<String>,
    modifier: Modifier = Modifier,
) {
    val description = state.detail.listing.description.resolve(preferredLanguageTags) ?: return
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.large,
        ),
    ) {
        Text(
            text = stringResource(R.string.appdetail_section_description),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            // F-Droid publishes descriptions in **reduced HTML** — <b>, <i>, <ul>, <a> — and showing
            // them as raw text means printing `<b>Food logging</b>` and `&amp;` on screen. Seen on the
            // emulator on the very first app opened, not a niche case: it affects almost every long
            // description in the catalogue.
            text = AnnotatedString.fromHtml(description.asDisplayableHtml()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.small),
        )
    }
}

/**
 * What is left to say after the header: minimum requirement and license.
 *
 * Version and size are **no longer here**: they moved to the top, where they get looked at. The
 * section did not disappear with them because `minSdk` and license remain two things read after
 * deciding one is interested, not before — and because they are the only two on the page that can be
 * missing together, in which case nothing is drawn.
 */
@Composable
private fun VersionFacts(state: AppDetailUiState.Ready, modifier: Modifier = Modifier) {
    val minSdk = state.detail.selection.versionOrNull()?.minSdk
    val license = state.detail.listing.license
    if (minSdk == null && license == null) return
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.large,
        ),
    ) {
        Text(
            text = stringResource(R.string.appdetail_section_details),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        minSdk?.let {
            Text(
                text = stringResource(R.string.appdetail_version_min_sdk, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.small),
            )
        }
        license?.let {
            Text(
                text = stringResource(R.string.appdetail_license, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Why the container could not be used.
 *
 * Five sentences and not one, for the same reason as the nine of [verificationMessage]: each leads to
 * a different thing to do. "Shizuku or root is needed" and "free up space" are two situations the user
 * can resolve, and reducing both to "it did not work" would remove the only useful part of the message.
 */
@Composable
private fun containerMessage(problem: ContainerProblem): String = when (problem) {
    is ContainerProblem.Unreadable ->
        stringResource(R.string.appdetail_container_unreadable, problem.reason)

    is ContainerProblem.IncompatibleAbi -> stringResource(
        R.string.appdetail_container_incompatible_abi,
        problem.available.joinToString(),
    )

    is ContainerProblem.NotEnoughSpace -> {
        val context = LocalContext.current
        stringResource(
            R.string.appdetail_container_not_enough_space,
            Formatter.formatShortFileSize(context, problem.needBytes),
            Formatter.formatShortFileSize(context, problem.freeBytes),
        )
    }

    ContainerProblem.ExpansionsNeedPrivilegedInstaller ->
        stringResource(R.string.appdetail_container_expansions_privileged)

    is ContainerProblem.ExpansionFailed ->
        stringResource(R.string.appdetail_container_expansion_failed, problem.reason)
}

/**
 * Why the pipeline refused the package.
 *
 * Every case has its own sentence, and that is not zeal: the difference between "the hash does not
 * match" and "the signature differs from the installed one" decides what the user can do next.
 * Reducing them all to "verification failed" would remove the message's only useful information.
 */
@Composable
private fun verificationMessage(outcome: VerificationOutcome): String = when (outcome) {
    is VerificationOutcome.Ok -> stringResource(R.string.appdetail_rejected_generic)
    is VerificationOutcome.Unreadable ->
        stringResource(R.string.appdetail_rejected_unreadable, outcome.reason)

    is VerificationOutcome.NotSigned ->
        stringResource(R.string.appdetail_rejected_not_signed, outcome.reason)

    is VerificationOutcome.SizeMismatch -> stringResource(R.string.appdetail_rejected_size)
    is VerificationOutcome.HashMismatch -> stringResource(R.string.appdetail_rejected_hash)
    is VerificationOutcome.PackageNameMismatch -> stringResource(
        R.string.appdetail_rejected_package_name,
        outcome.declared,
        outcome.actual,
    )

    is VerificationOutcome.SignerMismatchWithInstalled ->
        stringResource(R.string.appdetail_rejected_signer_installed)

    is VerificationOutcome.UnexpectedSigner ->
        stringResource(R.string.appdetail_rejected_unexpected_signer)

    is VerificationOutcome.ForeignSplit -> stringResource(
        R.string.appdetail_rejected_foreign_split,
        outcome.name,
        outcome.reason,
    )

    is VerificationOutcome.Downgrade -> stringResource(
        R.string.appdetail_rejected_downgrade,
        outcome.offeredVersionCode,
        outcome.installedVersionCode,
    )

    is VerificationOutcome.Incompatible -> stringResource(
        R.string.appdetail_rejected_incompatible,
        outcome.minSdk,
        outcome.deviceSdkInt,
    )
}

@Composable
private fun byteProgress(done: Long, total: Long?): String? {
    val context = LocalContext.current
    if (total == null) return Formatter.formatShortFileSize(context, done)
    return stringResource(
        R.string.appdetail_bytes_of,
        Formatter.formatShortFileSize(context, done),
        Formatter.formatShortFileSize(context, total),
    )
}

/**
 * A store description, made readable.
 *
 * F-Droid descriptions mix **reduced HTML** (`<b>`, `<i>`, `<ul>`, `<a>`) with **literal newlines**,
 * and the two have to be handled differently: the HTML must be interpreted, otherwise
 * `<b>Food logging</b>` shows up on screen; the newlines must become `<br>`, otherwise the HTML parser
 * treats them as spaces — as the standard requires — and a ten-item bullet list becomes one paragraph.
 * Both are needed: with only the first the text is formatted but unreadable, with only the second it
 * is spaced out but full of tags.
 */
private fun String.asDisplayableHtml(): String = replace("\n", "<br>")

/** The version the displayed facts refer to, when the rule picked one. */
private fun VersionSelection.Outcome.versionOrNull(): AppVersion? = when (this) {
    is VersionSelection.Outcome.Offer -> version
    is VersionSelection.Outcome.UpToDate -> version
    is VersionSelection.Outcome.SignerConflict -> available.firstOrNull()
    // The displayed facts (size, hash, signer) describe a version that could be installed. Here a
    // version exists, but it is not the one being offered: showing its facts would describe a beta as
    // if it were what is about to be downloaded.
    is VersionSelection.Outcome.OnlyOtherChannels -> null
    // Within the pin: the displayed facts must describe what pressing the button would install, not
    // the version the pin holds back.
    is VersionSelection.Outcome.Pinned -> offer?.version
    VersionSelection.Outcome.Incompatible, VersionSelection.Outcome.NothingInstallable -> null
}

/**
 * The minimum a touch target must measure.
 *
 * Not a number picked by eye: it is the threshold the accessibility check hooked into
 * `ScreenshotTest.capture` applies to every screen in both themes. A hand-built clickable row does not
 * go through `minimumInteractiveComponentSize`, which Material applies to its own controls and not to
 * a `Row`.
 */
private val MIN_TOUCH_TARGET = 48.dp

/** Between a version's size, date and channel. */
private const val FACT_SEPARATOR = " · "

private val HEADER_ICON_SIZE = 64.dp

private val CHECK_ICON_SIZE = 16.dp

/** The first eight hex pairs: as many as are really needed for an eyeball comparison. */
private const val SIGNER_FINGERPRINT_CHARS = 16

/**
 * `true` if asking for the notification permission makes sense.
 *
 * Below Android 13 the permission does not exist and the notification shows anyway; from 13 up it is
 * asked for only if not already granted. The "the user has already denied twice" case is not handled
 * here: the system stops showing the request on its own, and insisting would change nothing.
 */
private fun Context.needsNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

@Preview(name = "AppDetail light")
@Composable
private fun AppDetailScreenLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) { AppDetailPreviewContent() }
}

@Preview(name = "AppDetail dark")
@Composable
private fun AppDetailScreenDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) { AppDetailPreviewContent() }
}

@Composable
private fun AppDetailPreviewContent() {
    val version = AppVersion(
        versionName = "1.23.2",
        versionCode = 1_023_052,
        ref = VersionRef("preview"),
        sizeBytes = 9_400_000,
        minSdk = 23,
    )
    AppDetailScreen(
        uiState = AppDetailUiState.Ready(
            detail = AppDetail(
                listing = StoreListingDetail(
                    summary = StoreListingSummary(
                        storeId = StoreId.FDROID,
                        ref = StoreAppRef("org.fdroid.fdroid"),
                        title = "F-Droid",
                        packageName = "org.fdroid.fdroid",
                        summary = LocalizedText(mapOf("en" to "The app store with only free software.")),
                        developer = "F-Droid Limited",
                    ),
                    description = LocalizedText(
                        mapOf("en" to "F-Droid is an installable catalogue of free and open source software."),
                    ),
                    versions = listOf(version),
                ),
                installed = null,
                selection = VersionSelection.Outcome.Offer(version, isUpdate = false),
                stale = false,
            ),
            taxonomy = StoreTaxonomy(),
            storeName = "F-Droid",
            install = InstallUiState.Idle,
            crossStore = CrossStoreAvailability(
                availableOn = listOf(
                    StoreAvailability(
                        listing = AggregatedListing(
                            summary = StoreListingSummary(
                                storeId = StoreId.APKMIRROR,
                                ref = StoreAppRef("f-droid-limited/f-droid"),
                                title = "F-Droid",
                                packageName = "org.fdroid.fdroid",
                            ),
                        ),
                        listingId = 2,
                    ),
                ),
                unexploredStores = 3,
            ),
        ),
        preferredLanguageTags = listOf("en"),
        canInstallPackages = true,
        onBack = {},
        onInstall = {},
        onUninstall = {},
        onCancel = {},
        onDismissOutcome = {},
        onGrantInstallPermission = {},
        onInstallFromDownload = {},
        onUserAssistedDownload = {},
        storeDisplayName = { it.wireName },
        onOpenListing = { _, _ -> },
        onLookUpOtherStores = {},
        onConfirmMatch = {},
        onRejectMatch = {},
        onToggleVersionHistory = {},
        onShowVersionHistory = {},
        onRetryVersionHistory = {},
        onInstallVersion = {},
    )
}
