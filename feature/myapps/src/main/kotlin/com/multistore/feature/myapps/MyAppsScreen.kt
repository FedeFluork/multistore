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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.multistore.core.model.MyAppsSort
import com.multistore.core.ui.component.UpdateAllPanel
import com.multistore.core.ui.component.myAppsSortLabel

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
    // The field's text comes from `queryText` and **not** from `uiState.query`: see the doc on
    // `MyAppsViewModel.queryText`. They differ exactly while the state is being rebuilt, which is
    // when the field matters.
    val query by viewModel.queryText.collectAsStateWithLifecycle()
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
        query = query,
        onAppClick = onAppClick,
        onRequestUninstall = viewModel::requestUninstall,
        onConfirmUninstall = viewModel::confirmUninstall,
        onDismissUninstall = viewModel::dismissUninstall,
        onDismissFailure = viewModel::dismissFailure,
        modifier = modifier,
        onQueryChange = viewModel::onQueryChange,
        onSortChange = viewModel::setSort,
        onUpdateAll = viewModel::updateAll,
        onDismissUpdateAllResult = viewModel::dismissUpdateAllResult,
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
    /**
     * The text **in the field**, which is not `uiState.query`.
     *
     * Two properties that coincide on a still screen and diverge while somebody types: the field has
     * a single writer, the state is rebuilt by four asynchronous sources. Binding an editor to the
     * second is what made the search screen's caret jump backwards.
     */
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onSortChange: (MyAppsSort) -> Unit = {},
    onUpdateAll: () -> Unit = {},
    onDismissUpdateAllResult: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onDismissCheckResult: () -> Unit = {},
    onSetIgnoreUpdates: (InstalledAppItem, Boolean) -> Unit = { _, _ -> },
    onSetPinned: (InstalledAppItem, Boolean) -> Unit = { _, _ -> },
) {
    var sortDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            MultiStoreTopAppBar(
                title = stringResource(R.string.myapps_title),
                actions = {
                    val ready = uiState as? MyAppsUiState.Ready
                    // Both actions belong to a list that exists: with nothing installed there is
                    // nothing to sort and nothing to check.
                    if (ready != null) {
                        IconButton(onClick = { sortDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Sort,
                                contentDescription = stringResource(R.string.myapps_action_sort),
                            )
                        }
                    }
                    // The check disappears while it is running: a second tap would query the same
                    // stores for the same result.
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
                    query = query,
                    onQueryChange = onQueryChange,
                    onUpdateAll = onUpdateAll,
                    onDismissUpdateAllResult = onDismissUpdateAllResult,
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

    if (sortDialog) {
        val current = (uiState as? MyAppsUiState.Ready)?.sort ?: MyAppsSort.NAME
        SortDialog(
            selected = current,
            onSelect = { choice ->
                onSortChange(choice)
                sortDialog = false
            },
            onDismiss = { sortDialog = false },
        )
    }
}

/**
 * Choosing the order, from the list itself.
 *
 * The same four criteria as the Settings entry, with the same names — they come from
 * `myAppsSortLabel` in `:core:ui` — and choosing here **writes that setting**: there is one answer to
 * "how is this list arranged", not a transient one next to a remembered one.
 *
 * A dialog rather than a dropdown attached to the icon: four options with names as long as "Recently
 * installed" in a menu anchored to a top-bar corner would be a menu that opens off the edge in the
 * languages with the longest words, which are exactly the ones this app supports.
 */
@Composable
internal fun SortDialog(
    selected: MyAppsSort,
    onSelect: (MyAppsSort) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.myapps_sort_dialog_title)) },
        text = {
            Column {
                MyAppsSort.entries.forEach { choice ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = choice == selected,
                                // The row is the target, not just the button: a 20dp radio in a
                                // 48dp row is a target the accessibility check measures as the row
                                // and a user aims at as the dot.
                                role = Role.RadioButton,
                                onClick = { onSelect(choice) },
                            )
                            .heightIn(min = MIN_TOUCH_TARGET)
                            .padding(vertical = spacing.extraSmall),
                    ) {
                        RadioButton(selected = choice == selected, onClick = null)
                        Text(
                            text = myAppsSortLabel(choice),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = spacing.medium),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.myapps_sort_dialog_close))
            }
        },
    )
}

@Composable
private fun ReadyContent(
    state: MyAppsUiState.Ready,
    query: String,
    onQueryChange: (String) -> Unit,
    onUpdateAll: () -> Unit,
    onDismissUpdateAllResult: () -> Unit,
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

        // The same panel the Home draws, from the same use case: this is the screen that lists what
        // is updatable, one row at a time, with its own count — and until now the only way to apply
        // them all was to go back to the Home.
        //
        // `state.updatable` counts **every** installed app with something newer, not the rows a
        // search has left visible: the gesture acts on all of them, so the number next to it has to
        // be all of them. The panel is above the field for that reason — under it, next to two
        // filtered rows, "4 updates" would read as a statement about those two.
        UpdateAllPanel(
            state = state.updateAll,
            updatable = state.updatable,
            onUpdateAll = onUpdateAll,
            onDismissResult = onDismissUpdateAllResult,
            modifier = Modifier.padding(
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                top = spacing.small,
            ),
        )

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text(text = stringResource(R.string.myapps_search_label)) },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.myapps_search_clear),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.small),
        )

        // A query that matched nothing is not an empty screen: the apps are still installed, and the
        // field that produced the emptiness has to stay reachable so it can be cleared. That is why
        // `MyAppsUiState.Empty` is about the device and this is not.
        if (state.apps.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.myapps_no_matches_title),
                description = stringResource(R.string.myapps_no_matches_message, state.query),
            )
            return@Column
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

/**
 * The minimum a touch target must measure.
 *
 * Not a number picked by eye: it is the threshold the accessibility check hooked into
 * `ScreenshotTest.capture` applies to every screen in both themes. A hand-built selectable row does
 * not go through `minimumInteractiveComponentSize`, which Material applies to its own controls.
 */
private val MIN_TOUCH_TARGET = 48.dp

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
