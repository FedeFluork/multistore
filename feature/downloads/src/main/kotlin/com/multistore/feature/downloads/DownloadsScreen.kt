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
import java.io.File
import com.multistore.core.model.Sha256
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.multistore.core.data.repository.Staging
import com.multistore.core.ui.LaunchApp
import com.multistore.core.ui.Sharing

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

    // Recomputed on every return to the foreground: the user leaves to install the file, comes back,
    // and "Open" has to be there. A value captured once would be right only for rows whose app was
    // already on the device when the screen opened.
    var installed by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        installed++
        onPauseOrDispose { }
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
        onShare = context::shareApk,
        onOpen = { item -> LaunchApp.open(context, item.packageName) },
        // `installed` is read so the lambda is rebuilt after a return to the foreground; without it
        // Compose would keep the previous answer and the button would stay away.
        canOpen = { item -> installed >= 0 && LaunchApp.canOpen(context, item.packageName) },
    )
}

/**
 * Hands the staged APK to another app.
 *
 * ### Three things the text has to carry, and one it must not invent
 *
 * The name, the store it came from, and **the digest measured while the bytes were arriving** — not
 * the one the store published. That distinction is the whole value of sharing from here rather than
 * sharing a link: the receiving side gets the exact bytes this app checked, and a line saying what
 * they hash to. Where the download predates the column, or never finished, the line is simply
 * absent: a digest is either measured or not claimed.
 *
 * ### Why `Staging.shareableUri` and not `Uri.fromFile`
 *
 * A `file://` URI makes the receiving app throw `FileUriExposedException` from API 24 on, and the
 * crash lands on **their** side. The provider is declared in `:core:data`, next to the one object
 * that knows where these files live, and returns `null` rather than throwing for a path outside the
 * staging subtree — which is what a row written by an older version could hold.
 */
private fun Context.shareApk(item: DownloadItem) {
    val file = item.file ?: return
    val uri = Staging.shareableUri(this, file) ?: return
    val text = listOfNotNull(
        getString(R.string.downloads_share_text, item.title, item.storeName),
        item.sha256?.let { getString(R.string.downloads_share_hash, it.hex) },
    ).joinToString(separator = "\n")
    Sharing.shareFile(
        context = this,
        uri = uri,
        // What the store called it, because a container is a zip whose real type nobody agrees on:
        // claiming `application/vnd.android.package-archive` for an `.xapk` would offer it to apps
        // that cannot read it. The extension is the only thing anyone downstream can act on.
        mimeType = if (file.extension.equals("apk", ignoreCase = true)) {
            Sharing.MIME_APK
        } else {
            MIME_OCTET_STREAM
        },
        text = text,
        chooserTitle = getString(R.string.downloads_share),
    )
}

/** For a split container: honest about being bytes rather than wrong about being an APK. */
private const val MIME_OCTET_STREAM = "application/octet-stream"

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
    onShare: (DownloadItem) -> Unit = {},
    onOpen: (DownloadItem) -> Unit = {},
    /**
     * Whether this row's app is on the device **and** has something to open.
     *
     * A parameter and not a `PackageManager` call inside the row, for the reason the app page gives
     * for the same question: it is a fact about the device, and a composable that reads it itself
     * cannot be photographed — Robolectric has none of these packages installed, so the button would
     * be absent from every golden. The permissive default belongs to the goldens; the real screen
     * always passes the real answer.
     */
    canOpen: (DownloadItem) -> Boolean = { false },
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
                    onShare = onShare,
                    onOpen = onOpen,
                    canOpen = canOpen,
                )
                section(
                    titleRes = R.string.downloads_section_active,
                    items = uiState.active,
                    onInstall = onInstall,
                    onCancel = onCancel,
                    onDelete = onDelete,
                    onShare = onShare,
                    onOpen = onOpen,
                    canOpen = canOpen,
                )
                section(
                    titleRes = R.string.downloads_section_history,
                    items = uiState.history,
                    onInstall = onInstall,
                    onCancel = onCancel,
                    onDelete = onDelete,
                    onShare = onShare,
                    onOpen = onOpen,
                    canOpen = canOpen,
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
    onShare: (DownloadItem) -> Unit,
    onOpen: (DownloadItem) -> Unit,
    canOpen: (DownloadItem) -> Boolean,
) {
    if (items.isEmpty()) return
    item(key = "header-$titleRes") { SectionHeader(text = stringResource(titleRes)) }
    items(items = items, key = { it.id }) { item ->
        DownloadRow(
            item = item,
            onInstall = onInstall,
            onCancel = onCancel,
            onDelete = onDelete,
            onShare = onShare,
            onOpen = onOpen,
            canOpen = canOpen,
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
 * ### Which buttons, and why the set is written out rather than inferred
 *
 * Four situations, and each offers only what it can actually carry out:
 *
 * - the file is whole: **Delete**, **Share** and **Install**;
 * - the transfer is moving: **Cancel**, which stops it and keeps what has come down;
 * - the transfer is parked with a partial file: **Delete**, which is the way out of the state the
 *   previous button leaves. Without it, cancelling here would produce a row that can never leave
 *   this screen — restarting a transfer is the app page's job, not this one's;
 * - it is installed and the package has a launcher activity: **Open**, which is the one thing a
 *   history row can still do.
 *
 * ### Why Share is here and not only on the app's page
 *
 * A verified APK sits in `filesDir/staging`, a directory private to the app that no file manager can
 * open. Before this it was the one thing the user could not pass on even though they had it on the
 * device — and it is the more useful half of sharing, because the other end gets the exact bytes
 * this app checked rather than a link to a page that may serve something else.
 *
 * ### Why the filled one is always Install, and Open when there is nothing to install
 *
 * Install is what the row exists for. Cancel and Delete are outlined because each throws something
 * away — the second a whole verified file, the first the certainty of finishing — and a filled
 * button next to a progress bar invites the tap that undoes the megabytes already paid for. It is
 * the same asymmetry the app page has had since M1, and it has to stay the same in both places.
 * Share is outlined for a different reason: it is optional, and it must not compete with the
 * gesture the row was created for.
 */
@Composable
private fun DownloadRow(
    item: DownloadItem,
    onInstall: (DownloadItem) -> Unit,
    onCancel: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    onShare: (DownloadItem) -> Unit,
    onOpen: (DownloadItem) -> Unit,
    canOpen: (DownloadItem) -> Boolean,
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

        // Absent, not disabled: the button is there only if there is something to open. A package
        // with no launcher activity is ordinary — input methods, wallpapers, device administrators —
        // and a greyed-out "Open" would make people wonder what they did wrong.
        val openable = canOpen(item)
        if (item.cancellable || item.deletable || openable) {
            // Disabled while this screen is installing the row: the gestures act on the very file the
            // installer is reading, and a session that loses its APK halfway fails with a message
            // about the archive rather than about what the user just pressed.
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
                // Only on a whole file. A partial one would hand somebody bytes that verify against
                // nothing, and the receiving side has no way of telling.
                if (item.readyToInstall) {
                    OutlinedButton(onClick = { onShare(item) }, enabled = idle) {
                        Text(text = stringResource(R.string.downloads_share))
                    }
                }
                if (item.readyToInstall) {
                    Button(onClick = { onInstall(item) }, enabled = idle) {
                        Text(text = stringResource(R.string.downloads_install))
                    }
                }
                // The filled one when there is nothing to install: on a history row Open is the only
                // thing left to do, and it is the thing the user came for in the first place.
                if (openable) {
                    if (item.readyToInstall) {
                        OutlinedButton(onClick = { onOpen(item) }) {
                            Text(text = stringResource(R.string.downloads_open))
                        }
                    } else {
                        Button(onClick = { onOpen(item) }) {
                            Text(text = stringResource(R.string.downloads_open))
                        }
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
/**
 * A stand-in for a staged APK, in the preview and in the goldens.
 *
 * A path and not a real file: nothing here opens it. What it decides is which buttons the row draws,
 * because `hasFile` is derived from it — which is the point of the field being the `File` and not a
 * flag beside it.
 */
private val STAGED_FILE = File("/data/user/0/com.multistore/files/staging/1.apk")

/** A digest that looks like one: the share text quotes its first sixteen characters. */
private val STAGED_SHA256 = Sha256.parseOrNull("a1b2c3d4".repeat(8))

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
            file = STAGED_FILE,
            packageName = null,
            sha256 = null,
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
            file = STAGED_FILE,
            packageName = null,
            sha256 = null,
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
            file = STAGED_FILE,
            // The row that offers Share and Open, so it is the one carrying what both need: a
            // package to launch, and the digest measured while the bytes arrived.
            packageName = "org.fdroid.fdroid",
            sha256 = STAGED_SHA256,
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
            file = null,
            packageName = null,
            sha256 = null,
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
            file = null,
            packageName = null,
            sha256 = null,
            installedAt = null,
            createdAt = Instant.fromEpochSeconds(1_779_800_000),
            error = null,
        ),
    ),
)
