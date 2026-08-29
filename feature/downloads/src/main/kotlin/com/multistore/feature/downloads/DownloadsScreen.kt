package com.multistore.feature.downloads

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.DownloadState
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.ThemeMode
import com.multistore.core.ui.component.AppIcon
import com.multistore.core.ui.component.EmptyState
import com.multistore.core.ui.component.MultiStoreTopAppBar
import com.multistore.core.ui.component.appErrorMessage
import kotlin.time.Instant

/**
 * "Downloads": what is moving, what is waiting for a tap, and what already happened.
 *
 * ### Why the screen exists
 *
 * A transfer lives in a worker and survives the listing that started it. Until this screen the only
 * place it could be seen after walking away was a notification, outside the app and silenceable —
 * and a **finished** download that nobody installed had no surface at all: with the system
 * installer it stays in a private directory forever, and the one thing it needs is a tap.
 *
 * ### Why the history keeps rows whose file is gone
 *
 * "Which apps have I taken from where, and how did it go" is a question the rest of the app cannot
 * answer: "My apps" lists what is installed **now**, so it says nothing about a download that
 * failed, one that was deleted before being installed, or the same app fetched twice from two
 * stores. Those rows carry no file and no button — they are a record, and they are bounded by the
 * ceiling in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.userActions.collect { intent ->
            // FLAG_ACTIVITY_NEW_TASK: the intent comes from the system's `PendingIntent` and is
            // launched from a context that is not necessarily an activity's.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    DownloadsScreen(
        uiState = uiState,
        confirmation = confirmation,
        onInstall = viewModel::install,
        onCancel = viewModel::cancel,
        onDelete = viewModel::requestDelete,
        onClearHistory = viewModel::requestClearHistory,
        onConfirm = viewModel::confirm,
        onDismissConfirmation = viewModel::dismissConfirmation,
        modifier = modifier,
    )
}

/**
 * ViewModel-free variant, for previews and screenshot tests: a screenshot must depend only on the
 * state it is given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsScreen(
    uiState: DownloadsUiState,
    confirmation: DownloadsConfirmation?,
    onInstall: (DownloadItem) -> Unit,
    onCancel: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    onClearHistory: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirmation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val hasHistory = (uiState as? DownloadsUiState.Ready)?.history?.isNotEmpty() == true

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MultiStoreTopAppBar(
                title = stringResource(R.string.downloads_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    // The action appears only when there is a history to empty: a permanently
                    // visible button that does nothing on a fresh install teaches that it does
                    // nothing, and it is still there the day it would.
                    if (hasHistory) {
                        IconButton(onClick = onClearHistory) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription =
                                    stringResource(R.string.downloads_history_clear),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when (uiState) {
            DownloadsUiState.Loading -> Unit

            DownloadsUiState.Empty -> EmptyState(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.downloads_empty_title),
                description = stringResource(R.string.downloads_empty_message),
                modifier = Modifier.padding(innerPadding),
            )

            is DownloadsUiState.Ready -> LazyColumn(
                contentPadding = innerPadding,
                verticalArrangement = Arrangement.spacedBy(spacing.small),
                modifier = Modifier.fillMaxSize(),
            ) {
                section(
                    titleRes = R.string.downloads_section_ready,
                    items = uiState.readyToInstall,
                    onInstall = onInstall,
                    onCancel = onCancel,
                    onDelete = onDelete,
                )
                section(
                    titleRes = R.string.downloads_section_active,
                    items = uiState.active,
                    onInstall = onInstall,
                    onCancel = onCancel,
                    onDelete = onDelete,
                )
                section(
                    titleRes = R.string.downloads_section_history,
                    items = uiState.history,
                    onInstall = onInstall,
                    onCancel = onCancel,
                    onDelete = onDelete,
                )
            }
        }
    }

    when (confirmation) {
        null -> Unit
        is DownloadsConfirmation.Delete -> ConfirmDialog(
            title = stringResource(R.string.downloads_delete_confirm_title),
            message = stringResource(R.string.downloads_delete_confirm_message, confirmation.title),
            confirm = stringResource(R.string.downloads_delete),
            onConfirm = onConfirm,
            onDismiss = onDismissConfirmation,
        )

        DownloadsConfirmation.ClearHistory -> ConfirmDialog(
            title = stringResource(R.string.downloads_history_clear_confirm_title),
            message = stringResource(R.string.downloads_history_clear_confirm_message),
            confirm = stringResource(R.string.downloads_history_clear_action),
            onConfirm = onConfirm,
            onDismiss = onDismissConfirmation,
        )
    }
}

/**
 * A group with its heading, or nothing at all when it is empty.
 *
 * An empty section is not drawn — no heading over a void — because the three groups are almost
 * never all inhabited at once: the normal case is history alone.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.section(
    titleRes: Int,
    items: List<DownloadItem>,
    onInstall: (DownloadItem) -> Unit,
    onCancel: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$titleRes") { SectionHeader(text = stringResource(titleRes)) }
    items(items = items, key = { it.id }) { item ->
        DownloadRow(
            item = item,
            onInstall = onInstall,
            onCancel = onCancel,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.medium,
            bottom = spacing.extraSmall,
        ),
    )
}

/**
 * One download: icon, name, what it is doing, and — where there is something to do — the buttons.
 *
 * ### Which buttons, and why never more than two
 *
 * Three situations, and each one offers only what it can actually carry out:
 *
 * - the file is whole: **Delete** and **Install**;
 * - the transfer is moving: **Cancel**, which stops it and keeps what has come down;
 * - the transfer is parked with a partial file: **Delete**, which is the way out of the state the
 *   previous button leaves. Without it, cancelling here would produce a row that can never leave
 *   this screen — restarting a transfer is the app page's job, not this one's.
 *
 * A history row gets nothing, which is the point of it being history.
 *
 * ### Why the filled one is always Install
 *
 * Install is what the row exists for. Cancel and Delete are outlined because each throws something
 * away — the second a whole verified file, the first the certainty of finishing — and a filled
 * button next to a progress bar invites the tap that undoes the megabytes already paid for. It is
 * the same asymmetry the app page has had since M1, and it has to stay the same in both places.
 */
@Composable
private fun DownloadRow(
    item: DownloadItem,
    onInstall: (DownloadItem) -> Unit,
    onCancel: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(iconUrl = item.iconUrl)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = spacing.medium),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // A bar only while something is moving. A finished transfer with a full bar reads as
        // "nearly there" about something that is not going to move again by itself.
        if (item.state == DownloadState.RUNNING) {
            val fraction = item.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.small),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.small),
                )
            }
        }

        if (item.cancellable || item.deletable) {
            // Disabled while this screen is installing the row: the two gestures act on the very
            // file the installer is reading, and a session that loses its APK halfway fails with a
            // message about the archive rather than about what the user just pressed.
            val idle = item.install !is RowInstallState.Working
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.small, Alignment.End),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.small),
            ) {
                if (item.cancellable) {
                    OutlinedButton(onClick = { onCancel(item) }) {
                        Text(text = stringResource(R.string.downloads_cancel))
                    }
                }
                if (item.deletable) {
                    OutlinedButton(onClick = { onDelete(item) }, enabled = idle) {
                        Text(text = stringResource(R.string.downloads_delete))
                    }
                }
                if (item.readyToInstall) {
                    Button(onClick = { onInstall(item) }, enabled = idle) {
                        Text(text = stringResource(R.string.downloads_install))
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
}

/**
 * The one line under the name, and it answers exactly one question: what is the state of this file.
 *
 * The order of the branches puts what the screen is doing **now** before what the row records: a
 * failure the user has just caused by pressing Install matters more than the date it was downloaded.
 */
@Composable
private fun statusLine(item: DownloadItem): String = when (val install = item.install) {
    RowInstallState.Working -> stringResource(R.string.downloads_status_installing)
    RowInstallState.Rejected -> stringResource(R.string.downloads_status_rejected)
    is RowInstallState.Failed -> appErrorMessage(install.error)
    RowInstallState.Idle -> idleStatusLine(item)
}

@Composable
private fun idleStatusLine(item: DownloadItem): String {
    val context = LocalContext.current
    val size = item.bytesTotal
    return when {
        item.readyToInstall -> if (size != null) {
            stringResource(
                R.string.downloads_status_ready_sized,
                item.storeName,
                Formatter.formatShortFileSize(context, size),
            )
        } else {
            stringResource(R.string.downloads_status_ready, item.storeName)
        }

        item.state == DownloadState.RUNNING && size != null -> stringResource(
            R.string.downloads_status_progress,
            Formatter.formatShortFileSize(context, item.bytesDownloaded),
            Formatter.formatShortFileSize(context, size),
        )

        item.state == DownloadState.RUNNING -> stringResource(
            R.string.downloads_status_progress_unknown,
            Formatter.formatShortFileSize(context, item.bytesDownloaded),
        )

        item.state == DownloadState.QUEUED -> stringResource(R.string.downloads_status_queued)
        item.state == DownloadState.PAUSED -> stringResource(R.string.downloads_status_paused)
        item.state == DownloadState.VERIFYING -> stringResource(R.string.downloads_status_verifying)
        item.state == DownloadState.INSTALLING -> stringResource(R.string.downloads_status_installing)
        item.state == DownloadState.FAILED -> item.error?.let { appErrorMessage(it) }
            ?: stringResource(R.string.downloads_status_failed)

        // `DONE` says three different things, and only `installedAt` can tell them apart: the state
        // alone conflates "installed" with "deleted before it was ever installed".
        item.installedAt != null -> stringResource(R.string.downloads_status_installed, item.storeName)
        else -> stringResource(R.string.downloads_status_removed, item.storeName)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.downloads_confirm_cancel))
            }
        },
    )
}

@Preview
@Composable
private fun DownloadsScreenPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
        DownloadsScreen(
            uiState = PREVIEW_STATE,
            confirmation = null,
            onInstall = {},
            onCancel = {},
            onDelete = {},
            onClearHistory = {},
            onConfirm = {},
            onDismissConfirmation = {},
        )
    }
}

/**
 * The state the preview and the goldens share.
 *
 * It carries all three groups inhabited at once, which is rare in use and is the point of a golden:
 * the section headings, all three buttons and the three different sentences a history row can carry
 * all have to be looked at, and a state that showed one group at a time would need three
 * screenshots to say the same thing.
 *
 * "In progress" holds **two** rows on purpose, and they are the two halves of cancelling: one still
 * running, which offers Cancel, and one already parked, which offers the Delete that gets it off
 * the screen. A single running row would photograph the button and not the state it produces.
 *
 * It lives here rather than in the test so that the preview in the IDE and the committed golden are
 * **the same picture**. Two copies would drift, and the one that drifts is always the preview —
 * which is the one somebody looks at while changing the layout.
 */
internal val PREVIEW_STATE = DownloadsUiState.Ready(
    active = listOf(
        DownloadItem(
            id = 1,
            storeId = StoreId.APKMIRROR,
            ref = StoreAppRef("firefox"),
            title = "Firefox",
            iconUrl = null,
            storeName = "APKMirror",
            state = DownloadState.RUNNING,
            bytesDownloaded = 41_400_000,
            bytesTotal = 114_300_000,
            fraction = 0.36f,
            hasFile = true,
            installedAt = null,
            createdAt = Instant.fromEpochSeconds(1_780_000_000),
            error = null,
        ),
        DownloadItem(
            id = 5,
            storeId = StoreId.APKMODY,
            ref = StoreAppRef("spotify"),
            title = "Spotify",
            iconUrl = null,
            storeName = "APKMody",
            state = DownloadState.PAUSED,
            bytesDownloaded = 12_800_000,
            bytesTotal = 96_400_000,
            fraction = 0.13f,
            hasFile = true,
            installedAt = null,
            createdAt = Instant.fromEpochSeconds(1_779_995_000),
            error = null,
        ),
    ),
    readyToInstall = listOf(
        DownloadItem(
            id = 2,
            storeId = StoreId.FDROID,
            ref = StoreAppRef("org.fdroid.fdroid"),
            title = "F-Droid",
            iconUrl = null,
            storeName = "F-Droid",
            state = DownloadState.READY,
            bytesDownloaded = 8_647_000,
            bytesTotal = 8_647_000,
            fraction = 1f,
            hasFile = true,
            installedAt = null,
            createdAt = Instant.fromEpochSeconds(1_779_990_000),
            error = null,
        ),
    ),
    history = listOf(
        DownloadItem(
            id = 3,
            storeId = StoreId.UPTODOWN,
            ref = StoreAppRef("telegram"),
            title = "Telegram",
            iconUrl = null,
            storeName = "Uptodown",
            state = DownloadState.DONE,
            bytesDownloaded = 72_100_000,
            bytesTotal = 72_100_000,
            fraction = 1f,
            hasFile = false,
            installedAt = Instant.fromEpochSeconds(1_779_900_000),
            createdAt = Instant.fromEpochSeconds(1_779_899_000),
            error = null,
        ),
        DownloadItem(
            id = 4,
            storeId = StoreId.APKCOMBO,
            ref = StoreAppRef("duolingo"),
            title = "Duolingo",
            iconUrl = null,
            storeName = "APKCombo",
            state = DownloadState.DONE,
            bytesDownloaded = 238_000_000,
            bytesTotal = 238_000_000,
            fraction = 1f,
            hasFile = false,
            installedAt = null,
            createdAt = Instant.fromEpochSeconds(1_779_800_000),
            error = null,
        ),
    ),
)
