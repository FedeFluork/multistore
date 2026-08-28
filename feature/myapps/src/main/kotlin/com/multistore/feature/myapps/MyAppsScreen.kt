package com.multistore.feature.myapps

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.common.result.AppError
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.ThemeMode
import com.multistore.core.ui.component.AppIcon
import com.multistore.core.ui.component.EmptyState
import com.multistore.core.ui.component.MultiStoreTopAppBar
import com.multistore.core.ui.component.appErrorMessage
import com.multistore.core.ui.component.installFailureExplanation
import kotlin.time.Instant

/**
 * "My apps": what was installed **through MultiStore**, and from where.
 *
 * The scope is a deliberate decision. Listing every package on the device would be technically
 * possible — `QUERY_ALL_PACKAGES` is there — but it would mean putting rows into a list titled "my
 * apps" on which the only offered action, updating, would not work: without knowing which store an app
 * came from there is no store to update it from, and picking the first one with a higher `versionCode`
 * makes the update fail on a signature mismatch.
 *
 * Every row carries two durable decisions — **pause notices** and **pin to this version** — which used
 * not to be here even though the columns and the writes already were. They were missing because nobody
 * read them: a switch that changes nothing is worse than an absent switch. Now the update check reads
 * them, and the pin is read by the detail page too, so it makes sense to be able to touch them.
 */
@Composable
fun MyAppsScreen(
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyAppsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // On every return to the foreground, not only at startup: the user may have uninstalled something
    // from the system settings while away, and without reconciliation the list would show a ghost on
    // which uninstalling would fail.
    LifecycleResumeEffect(viewModel) {
        viewModel.reconcile()
        onPauseOrDispose { }
    }

    LaunchedUserActions(viewModel) { intent ->
        // FLAG_ACTIVITY_NEW_TASK: the intent comes from the system's `PendingIntent` and is launched
        // from a context that is not necessarily an activity's.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    MyAppsScreen(
        uiState = uiState,
        onAppClick = onAppClick,
        onRequestUninstall = viewModel::requestUninstall,
        onConfirmUninstall = viewModel::confirmUninstall,
        onDismissUninstall = viewModel::dismissUninstall,
        onDismissFailure = viewModel::dismissFailure,
        modifier = modifier,
        onCheckForUpdates = viewModel::checkForUpdates,
        onDismissCheckResult = viewModel::dismissCheckResult,
        onSetIgnoreUpdates = viewModel::setIgnoreUpdates,
        onSetPinned = viewModel::setPinnedToInstalled,
    )
}

/** The `Intent`s only a foreground UI may launch. See [MyAppsViewModel.userActions]. */
@Composable
private fun LaunchedUserActions(viewModel: MyAppsViewModel, onIntent: (Intent) -> Unit) {
    LaunchedEffect(viewModel) {
        viewModel.userActions.collect(onIntent)
    }
}

/**
 * ViewModel-free variant, for previews and screenshot tests: a screenshot must depend only on the
 * state it is given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyAppsScreen(
    uiState: MyAppsUiState,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onRequestUninstall: (InstalledAppItem) -> Unit,
    onConfirmUninstall: () -> Unit,
    onDismissUninstall: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
    onCheckForUpdates: () -> Unit = {},
    onDismissCheckResult: () -> Unit = {},
    onSetIgnoreUpdates: (InstalledAppItem, Boolean) -> Unit = { _, _ -> },
    onSetPinned: (InstalledAppItem, Boolean) -> Unit = { _, _ -> },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MultiStoreTopAppBar(
                title = stringResource(R.string.myapps_title),
                actions = {
                    // The button is there only when there is something to check, and disappears while
                    // the check is running: a second tap would query the same stores for the same
                    // result.
                    val ready = uiState as? MyAppsUiState.Ready
                    if (ready != null && ready.check !is UpdateCheckUiState.Running) {
                        IconButton(onClick = onCheckForUpdates) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.myapps_action_check),
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
                MyAppsUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                MyAppsUiState.Empty -> EmptyState(
                    icon = Icons.Rounded.Apps,
                    title = stringResource(R.string.myapps_empty_state_title),
                    description = stringResource(R.string.myapps_empty_state_message),
                )

                is MyAppsUiState.Ready -> ReadyContent(
                    state = uiState,
                    onAppClick = onAppClick,
                    onRequestUninstall = onRequestUninstall,
                    onDismissCheckResult = onDismissCheckResult,
                    onSetIgnoreUpdates = onSetIgnoreUpdates,
                    onSetPinned = onSetPinned,
                )
            }
        }
    }

    when (val uninstall = (uiState as? MyAppsUiState.Ready)?.uninstall) {
        is UninstallUiState.Confirming -> UninstallConfirmDialog(
            label = uninstall.label,
            onConfirm = onConfirmUninstall,
            onDismiss = onDismissUninstall,
        )

        is UninstallUiState.Failed -> UninstallFailureDialog(
            // A refused uninstall also carries a `PackageInstaller` code, and they are the same seven
            // constants: `STATUS_FAILURE_BLOCKED` here means a device administrator or a ROM feature
            // that will not let that app be removed. The raw message is already shown by the row below,
            // so only the explanation is passed here.
            message = (uninstall.error as? AppError.InstallFailed)
                ?.let { installFailureExplanation(it) }
                ?: appErrorMessage(uninstall.error),
            systemMessage = uninstall.systemMessage,
            onDismiss = onDismissFailure,
        )

        UninstallUiState.Idle, is UninstallUiState.InProgress, null -> Unit
    }
}

@Composable
private fun ReadyContent(
    state: MyAppsUiState.Ready,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onRequestUninstall: (InstalledAppItem) -> Unit,
    onDismissCheckResult: () -> Unit,
    onSetIgnoreUpdates: (InstalledAppItem, Boolean) -> Unit,
    onSetPinned: (InstalledAppItem, Boolean) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.uninstall is UninstallUiState.InProgress) UninstallProgressBanner()
        when (val check = state.check) {
            UpdateCheckUiState.Running -> CheckProgressBanner()
            is UpdateCheckUiState.Incomplete -> CheckIncompleteBanner(
                stores = check.stores,
                onDismiss = onDismissCheckResult,
            )

            UpdateCheckUiState.Idle -> Unit
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = pluralStringResource(
                        R.plurals.myapps_installed_count,
                        state.apps.size,
                        state.apps.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = spacing.screenHorizontal,
                        end = spacing.screenHorizontal,
                        top = spacing.large,
                        bottom = spacing.small,
                    ),
                )
            }
            items(items = state.apps, key = { it.app.packageName }) { item ->
                InstalledAppRow(
                    item = item,
                    onClick = {
                        val storeId = item.app.sourceStoreId
                        val ref = item.app.sourceRef
                        if (storeId != null && ref != null) onAppClick(storeId, ref)
                    },
                    onUninstall = { onRequestUninstall(item) },
                    onSetIgnoreUpdates = { ignore -> onSetIgnoreUpdates(item, ignore) },
                    onSetPinned = { pinned -> onSetPinned(item, pinned) },
                )
            }
        }
    }
}

@Composable
private fun InstalledAppRow(
    item: InstalledAppItem,
    onClick: () -> Unit,
    onUninstall: () -> Unit,
    onSetIgnoreUpdates: (Boolean) -> Unit,
    onSetPinned: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A row whose origin is unknown has no detail page to open, and must not look as if it did:
            // without `sourceStoreId` the tap does nothing, so it is better for the tap not to be there.
            .then(if (item.hasDetail) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = spacing.screenHorizontal, top = spacing.medium, bottom = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(iconUrl = item.app.iconUrl)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = spacing.large),
        ) {
            Text(
                text = item.app.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.myapps_version, item.app.versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.storeName?.let { name ->
                Text(
                    text = stringResource(R.string.myapps_installed_from, name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            UpdateLine(state = item.update)
        }
        RowActions(
            item = item,
            onUninstall = onUninstall,
            onSetIgnoreUpdates = onSetIgnoreUpdates,
            onSetPinned = onSetPinned,
        )
    }
}

/**
 * The row that says what happens to this app's updates.
 *
 * "Up to date" is the only case where nothing is written: it is the normal state, and repeating it on
 * twenty rows would make the few that have something to say unreadable. Every other state concerns a
 * user decision or a store limitation, and those have to be said.
 */
@Composable
private fun UpdateLine(state: UpdateState, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    val (text, emphasised) = when (state) {
        UpdateState.UpToDate -> return
        is UpdateState.Available ->
            stringResource(R.string.myapps_update_available, state.versionName) to true

        is UpdateState.Paused -> when {
            state.available -> stringResource(R.string.myapps_update_paused_with_pending)
            else -> stringResource(R.string.myapps_update_paused)
        } to false

        is UpdateState.Pinned ->
            stringResource(R.string.myapps_update_pinned, state.versionCode, state.heldBack) to false

        UpdateState.Undeterminable -> stringResource(R.string.myapps_update_undeterminable) to false
        UpdateState.NoChannel -> stringResource(R.string.myapps_update_no_channel) to false
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (emphasised) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.padding(top = spacing.extraSmall),
    )
}

/**
 * A row's actions: uninstall, and the two update decisions.
 *
 * Uninstalling keeps a button of its own because it is the most used action and the most serious; the
 * other two live in a menu, because they are durable settings and not gestures to be made in a hurry.
 * This menu did not exist before, and not by oversight: nobody read the two columns it governs, and a
 * switch that changes nothing is worse than an absent switch.
 */
@Composable
private fun RowActions(
    item: InstalledAppItem,
    onUninstall: () -> Unit,
    onSetIgnoreUpdates: (Boolean) -> Unit,
    onSetPinned: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onUninstall) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                // The app name inside the description: with a list of twenty rows, "Uninstall" repeated
                // twenty times does not tell TalkBack which one is being touched.
                contentDescription = stringResource(R.string.myapps_uninstall_app, item.app.label),
            )
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.myapps_more_actions, item.app.label),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val paused = item.app.ignoreUpdates
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (paused) {
                                stringResource(R.string.myapps_action_resume_updates)
                            } else {
                                stringResource(R.string.myapps_action_pause_updates)
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSetIgnoreUpdates(!paused)
                    },
                )
                val pinned = item.app.pinnedVersionCode != null
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (pinned) {
                                stringResource(R.string.myapps_action_unpin)
                            } else {
                                stringResource(R.string.myapps_action_pin, item.app.versionName)
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSetPinned(!pinned)
                    },
                )
            }
        }
    }
}

@Composable
private fun CheckProgressBanner(modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = stringResource(R.string.myapps_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.small),
            )
        }
    }
}

/**
 * The check finished, but not everybody answered.
 *
 * Not an error and not to be shown as one: what the other stores said is valid, and the list already
 * reflects it. Saying how many are missing is the only thing that distinguishes "there are no updates"
 * from "there are no updates **from those who answered**".
 */
@Composable
private fun CheckIncompleteBanner(
    stores: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                start = spacing.large,
                end = spacing.small,
                top = spacing.small,
                bottom = spacing.small,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.myapps_check_incomplete, stores, stores),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.myapps_check_dismiss))
            }
        }
    }
}

@Composable
private fun UninstallProgressBanner(modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = stringResource(R.string.myapps_uninstalling),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.small),
            )
        }
    }
}

@Composable
private fun UninstallConfirmDialog(
    label: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.myapps_uninstall_confirm_title, label)) },
        text = { Text(text = stringResource(R.string.myapps_uninstall_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.myapps_uninstall_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.myapps_uninstall_dismiss))
            }
        },
    )
}

@Composable
private fun UninstallFailureDialog(
    message: String,
    systemMessage: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.myapps_uninstall_failed_title)) },
        text = {
            Column {
                Text(text = message)
                // The raw `PackageInstaller` text is not translated and not for the ordinary user, but
                // it is the only thing telling two different refusals apart: without it, a bug report
                // becomes "it does not uninstall".
                systemMessage?.let { raw ->
                    Text(
                        text = raw,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.myapps_uninstall_failed_dismiss))
            }
        },
    )
}

@Preview(name = "MyApps light")
@Composable
private fun MyAppsScreenLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) { MyAppsPreviewContent() }
}

@Preview(name = "MyApps dark")
@Composable
private fun MyAppsScreenDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) { MyAppsPreviewContent() }
}

@Composable
private fun MyAppsPreviewContent() {
    MyAppsScreen(
        uiState = MyAppsUiState.Ready(
            apps = listOf(
                InstalledAppItem(
                    app = previewApp("org.fdroid.fdroid", "F-Droid", "1.23.2"),
                    storeName = "F-Droid",
                ),
            ),
            uninstall = UninstallUiState.Idle,
        ),
        onAppClick = { _, _ -> },
        onRequestUninstall = {},
        onConfirmUninstall = {},
        onDismissUninstall = {},
        onDismissFailure = {},
    )
}

private fun previewApp(packageName: String, label: String, versionName: String) = InstalledApp(
    packageName = packageName,
    label = label,
    versionName = versionName,
    versionCode = 1_023_052,
    signerSha256 = null,
    installedAt = Instant.fromEpochMilliseconds(1_787_316_712_615L),
    installerKind = InstallerKind.SESSION,
    sourceStoreId = StoreId.FDROID,
    sourceRef = StoreAppRef(packageName),
)
