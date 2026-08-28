package com.multistore.feature.home

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.data.repository.HomeIndex
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.SelfUpdateOffer
import com.multistore.core.model.Category
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.ui.component.AppIcon
import com.multistore.core.ui.component.AppListItem
import com.multistore.core.ui.component.EmptyState
import com.multistore.core.ui.component.MultiStoreTopAppBar
import com.multistore.core.ui.component.appErrorMessage
import com.multistore.core.ui.rememberPreferredLanguageTags

/**
 * Home screen: the state of the local catalogue and the recently updated apps.
 *
 * It makes no network requests to fill itself. At first launch it has nothing to show, and that is
 * the one case where its job is a different one: telling the story of the sync — what is being
 * downloaded, how far along it is, and what to do if the network is metered.
 */
@Composable
fun HomeScreen(
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onBrowseCatalogue: (StoreId, String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.userActions.collect { intent ->
            // FLAG_ACTIVITY_NEW_TASK: the intent comes from the system's `PendingIntent`, and
            // whoever launches it is not necessarily an activity context.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    HomeScreen(
        uiState = uiState,
        preferredLanguageTags = rememberPreferredLanguageTags(),
        onAppClick = onAppClick,
        onBrowseCatalogue = onBrowseCatalogue,
        onSync = { viewModel.sync() },
        onSyncWithConsent = { viewModel.sync(userConsented = true) },
        onDismissMeteredConsent = viewModel::dismissMeteredConsent,
        onDismissFailure = viewModel::dismissFailure,
        modifier = modifier,
        onUpdateAll = viewModel::updateAll,
        onDismissUpdateAllResult = viewModel::dismissUpdateAllResult,
        onUpdateSelf = viewModel::updateSelf,
        onDismissSelfUpdateFailure = viewModel::dismissSelfUpdateFailure,
    )
}

/**
 * ViewModel-free variant, for previews and screenshot tests: a screenshot must depend only on the
 * state it is given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    uiState: HomeUiState,
    preferredLanguageTags: List<String>,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onBrowseCatalogue: (StoreId, String?) -> Unit,
    onSync: () -> Unit,
    onSyncWithConsent: () -> Unit,
    onDismissMeteredConsent: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
    onUpdateAll: () -> Unit = {},
    onDismissUpdateAllResult: () -> Unit = {},
    onUpdateSelf: () -> Unit = {},
    onDismissSelfUpdateFailure: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MultiStoreTopAppBar(
                title = stringResource(R.string.home_title),
                actions = {
                    val ready = uiState as? HomeUiState.Ready
                    if (ready != null && ready.index !is IndexStatus.Syncing) {
                        IconButton(onClick = onSync) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.home_refresh_action),
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
                HomeUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                HomeUiState.NoIndexedStore -> EmptyState(
                    icon = Icons.Rounded.CloudDownload,
                    title = stringResource(R.string.home_no_indexed_store_title),
                    description = stringResource(R.string.home_no_indexed_store_message),
                )

                is HomeUiState.Ready -> ReadyContent(
                    state = uiState,
                    preferredLanguageTags = preferredLanguageTags,
                    onAppClick = onAppClick,
                    onBrowseCatalogue = onBrowseCatalogue,
                    onSync = onSync,
                    onDismissFailure = onDismissFailure,
                    onUpdateAll = onUpdateAll,
                    onDismissUpdateAllResult = onDismissUpdateAllResult,
                    onUpdateSelf = onUpdateSelf,
                    onDismissSelfUpdateFailure = onDismissSelfUpdateFailure,
                )
            }
        }
    }

    if ((uiState as? HomeUiState.Ready)?.meteredConsentRequired == true) {
        MeteredConsentDialog(
            onConfirm = onSyncWithConsent,
            onDismiss = onDismissMeteredConsent,
        )
    }
}

@Composable
private fun ReadyContent(
    state: HomeUiState.Ready,
    preferredLanguageTags: List<String>,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onBrowseCatalogue: (StoreId, String?) -> Unit,
    onSync: () -> Unit,
    onDismissFailure: () -> Unit,
    onUpdateAll: () -> Unit,
    onDismissUpdateAllResult: () -> Unit,
    onUpdateSelf: () -> Unit,
    onDismissSelfUpdateFailure: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Above everything, sync banners included: it is the only thing on this screen that concerns
        // the user's phone rather than a store's catalogue.
        UpdatesCard(
            updates = state.updates,
            progress = state.updateAll,
            onUpdateAll = onUpdateAll,
            onDismissResult = onDismissUpdateAllResult,
            onAppClick = onAppClick,
        )

        // Above the other apps to update, and not among them: it is the only one whose update kills
        // the process being looked at, so it has to be seen before starting others.
        SelfUpdateCard(
            offer = state.selfUpdate,
            progress = state.selfUpdateProgress,
            onUpdate = onUpdateSelf,
            onDismissFailure = onDismissSelfUpdateFailure,
        )

        when (val index = state.index) {
            is IndexStatus.Syncing -> SyncProgressBanner(index)
            is IndexStatus.Failed -> SyncFailureBanner(
                status = index,
                onRetry = onSync,
                onDismiss = onDismissFailure,
            )

            IndexStatus.NeverSynced, is IndexStatus.Synced -> Unit
        }

        HomeList(
            state = state,
            preferredLanguageTags = preferredLanguageTags,
            onAppClick = onAppClick,
            onBrowseCatalogue = onBrowseCatalogue,
            onSync = onSync,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The Home's scrolling body: what comes from the remote index first, then the local catalogue.
 *
 * A single list rather than one per source, because the two remote sections must appear **even when
 * the local catalogue is not there yet**. The Home's body used to exist only if F-Droid had already
 * been synced: at first launch one saw a "download the catalogue" button and nothing else. With the
 * remote index that first launch has something to show — tens of kilobytes against eighteen megabytes
 * — and keeping it inside the "catalogue ready" branch would have made it invisible exactly when it
 * is needed.
 *
 * The order is deliberate: the two remote sections at the top, the local catalogue below. Not an
 * aesthetic preference — "most popular" and "new" are thirty entries, "recently updated" has thirty
 * with 4,269 behind them, and it is the one that needs the category shortcuts next to it.
 */
@Composable
private fun HomeList(
    state: HomeUiState.Ready,
    preferredLanguageTags: List<String>,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onBrowseCatalogue: (StoreId, String?) -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    LazyColumn(modifier = modifier.fillMaxSize()) {
        indexCarousel(
            titleRes = R.string.home_popular_title,
            apps = state.remoteIndex.popular,
            onAppClick = onAppClick,
        )
        indexCarousel(
            titleRes = R.string.home_new_title,
            apps = state.remoteIndex.recent,
            onAppClick = onAppClick,
        )

        if (state.categories.isNotEmpty()) {
            item {
                CategoryShortcuts(
                    categories = state.categories,
                    preferredLanguageTags = preferredLanguageTags,
                    onBrowse = { categoryId -> onBrowseCatalogue(state.storeId, categoryId) },
                )
            }
        }

        when {
            state.recentlyUpdated.isNotEmpty() -> {
                item {
                    SectionTitle(
                        title = stringResource(R.string.home_recently_updated_title),
                        subtitle = state.index.entryCount()?.let { size ->
                            pluralStringResource(R.plurals.home_catalogue_size, size, size)
                        },
                    )
                }
                items(
                    items = state.recentlyUpdated,
                    key = { "${it.storeId.wireName}/${it.ref.value}" },
                ) { app ->
                    AppListItem(
                        summary = app,
                        preferredLanguageTags = preferredLanguageTags,
                        onClick = { onAppClick(app.storeId, app.ref) },
                    )
                }
            }

            // The catalogue is not there yet **and** we are not downloading it: this is first launch.
            state.index is IndexStatus.NeverSynced -> item {
                FirstSyncPrompt(
                    onSync = onSync,
                    // `fillParentMaxHeight` only when there is nothing else: inside a list that
                    // already carries the two remote sections, an empty state as tall as the display
                    // would push what there is to see out of view.
                    modifier = if (state.remoteIndex.isEmpty) Modifier.fillParentMaxHeight() else Modifier,
                )
            }

            state.index is IndexStatus.Synced -> item {
                EmptyState(
                    icon = Icons.Rounded.CloudDownload,
                    title = stringResource(R.string.home_catalogue_empty_title),
                    description = stringResource(R.string.home_catalogue_empty_message),
                    modifier = if (state.remoteIndex.isEmpty) Modifier.fillParentMaxHeight() else Modifier,
                )
            }

            else -> Unit
        }

        item { Box(modifier = Modifier.padding(bottom = spacing.large)) }
    }
}

/**
 * A horizontal row of apps from the remote index, or nothing.
 *
 * `LazyListScope.` rather than `@Composable` because it has to be able to **add no element at all**: a
 * composable returning early would still leave the `item` in the list, that is an empty space where
 * there is no section.
 */
private fun LazyListScope.indexCarousel(
    @StringRes titleRes: Int,
    apps: List<StoreListingSummary>,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
) {
    if (apps.isEmpty()) return
    item { SectionTitle(title = stringResource(titleRes), subtitle = null) }
    item {
        val spacing = LocalSpacing.current
        LazyRow(
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            // The key is `(store, ref)`, and the list arrives already deduplicated on that pair from
            // the repository. Deduplication lives there and not here for the general rule: **before
            // using a domain identifier as a list key, verify it is unique in that list**, and the
            // only place that can guarantee it is whoever builds the list.
            items(items = apps, key = { "${it.storeId.wireName}/${it.ref.value}" }) { app ->
                IndexCard(app = app, onClick = { onAppClick(app.storeId, app.ref) })
            }
        }
    }
}

@Composable
private fun IndexCard(app: StoreListingSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .width(INDEX_CARD_WIDTH)
            .clickable(onClick = onClick)
            .padding(vertical = spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(iconUrl = app.iconUrl, size = INDEX_CARD_ICON)
        Text(
            text = app.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.small),
        )
        // The store it comes from, and that is not decoration: the same app can appear in both
        // sections with two different origins, and tapping it opens **that** listing.
        Text(
            text = app.storeId.wireName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.large,
            bottom = spacing.small,
        ),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The category shortcuts: the only way into the catalogue for whoever does not already know what to
 * search for.
 *
 * With 4,269 packages downloaded, "recently updated" shows thirty and search expects the name to be
 * known already. Without this row, the rest of the index is not reachable from anywhere in the app.
 *
 * The first chip opens the **whole** catalogue, and with 108 categories that is not an extra but the
 * main route: [HOME_CATEGORY_LIMIT] are shown here, picked from the most populated, and the other
 * ninety-eight are reachable from the picker on the screen that chip opens. Showing them all here
 * would be a horizontal scroll nobody reaches the end of.
 *
 * The names arrive from the store already localised rather than from `strings.xml`: they are network
 * data, not interface text.
 */
@Composable
private fun CategoryShortcuts(
    categories: List<Category>,
    preferredLanguageTags: List<String>,
    onBrowse: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.padding(top = spacing.large)) {
        Text(
            text = stringResource(R.string.home_categories_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.small),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            AssistChip(
                onClick = { onBrowse(null) },
                label = { Text(text = stringResource(R.string.home_categories_all)) },
            )
            categories.take(HOME_CATEGORY_LIMIT).forEach { category ->
                AssistChip(
                    onClick = { onBrowse(category.id) },
                    label = { Text(text = category.displayName(preferredLanguageTags)) },
                )
            }
        }
    }
}

/**
 * MultiStore's own update.
 *
 * Not a row of [UpdatesCard] because it is not the same thing and does not end the same way. The apps
 * in that list come from `installed_apps`, have an update channel and a listing ref; MultiStore has
 * none of the three — see the note on `InstallPlan.storeId` — and above all **updating it kills the
 * process halfway through the commit**. Putting it in line with the others would mean an "update all"
 * that interrupts itself, which is why the loop already puts it last.
 */
@Composable
private fun SelfUpdateCard(
    offer: SelfUpdateOffer?,
    progress: SelfUpdateUiState,
    onUpdate: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (offer == null) return

    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = stringResource(R.string.home_self_update_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // "0.1.0 → 0.2.0" rather than "update available": the second sentence does not say what
            // is about to happen, and on an app installed from alternative sources knowing where you
            // are going from and to is the only thing that lets you decide.
            Text(
                text = stringResource(
                    R.string.home_self_update_versions,
                    offer.installedVersionName,
                    offer.release.versionName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
            offer.release.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = SELF_UPDATE_NOTES_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = spacing.extraSmall),
                )
            }

            when (progress) {
                SelfUpdateUiState.Idle -> Button(
                    onClick = onUpdate,
                    modifier = Modifier.padding(top = spacing.medium),
                ) {
                    Text(text = stringResource(R.string.home_self_update_action))
                }

                is SelfUpdateUiState.Downloading -> {
                    Text(
                        text = stringResource(R.string.home_self_update_downloading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.medium),
                    )
                    // A determinate bar only if the server declares the total: a bar pretending to
                    // know how much is left is worse than one that just spins.
                    val total = progress.bytesTotal
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.bytesDownloaded.toFloat() / total },
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

                SelfUpdateUiState.Installing -> Text(
                    text = stringResource(R.string.home_self_update_installing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.medium),
                )

                is SelfUpdateUiState.Failed -> {
                    Text(
                        text = appErrorMessage(progress.error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = spacing.medium),
                    )
                    TextButton(onClick = onDismissFailure) {
                        Text(text = stringResource(R.string.home_self_update_dismiss))
                    }
                }
            }
        }
    }
}

/**
 * "Updates available", with the button that applies them all.
 *
 * Not a screen of its own: the list is short by construction — they are the apps the user installed
 * **from here** — and one more destination for three rows would be one more tap to see them. The rows
 * lead to the detail page, where everything else is.
 *
 * The progress reads "2 of 5" rather than being a bar because with only `SessionInstaller` every app
 * opens the system confirmation screen: the timing is decided by the user, not by the network, and a
 * bar advancing on its own would state something false.
 */
@Composable
private fun UpdatesCard(
    updates: List<InstalledAppUpdate>,
    progress: UpdateAllUiState,
    onUpdateAll: () -> Unit,
    onDismissResult: () -> Unit,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    // No updates and no outcome to show: the section does not exist. A card saying "0 updates" takes
    // up the catalogue's space to say nothing.
    if (updates.isEmpty() && progress is UpdateAllUiState.Idle) return

    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            when (progress) {
                is UpdateAllUiState.Running -> {
                    Text(
                        text = stringResource(
                            R.string.home_updates_applying,
                            progress.done + 1,
                            progress.total,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = progress.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = spacing.extraSmall),
                    )
                    LinearProgressIndicator(
                        progress = { (progress.done + 1).toFloat() / progress.total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.small),
                    )
                }

                is UpdateAllUiState.Finished -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        // Successes and failures in the same sentence: saying only "3 updated" when
                        // two were cancelled would leave the user wondering why the list did not
                        // empty.
                        text = stringResource(
                            R.string.home_updates_finished,
                            progress.installed,
                            progress.failed,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissResult) {
                        Text(text = stringResource(R.string.home_updates_dismiss))
                    }
                }

                UpdateAllUiState.Idle -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.home_updates_title,
                                updates.size,
                                updates.size,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = onUpdateAll) {
                            Text(text = stringResource(R.string.home_updates_update_all))
                        }
                    }
                    updates.forEach { update ->
                        UpdateRow(update = update, onAppClick = onAppClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(
    update: InstalledAppUpdate,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val channel = update.channel ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onAppClick(channel.storeId, channel.ref) }
            .padding(vertical = spacing.small),
    ) {
        AppIcon(iconUrl = channel.iconUrl)
        Column(modifier = Modifier.padding(start = spacing.large)) {
            Text(
                text = channel.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // From where to where: "2.22 → 2.30" says in three characters what two lines of text
                // would say worse.
                text = stringResource(
                    R.string.home_updates_version_change,
                    update.app.versionName,
                    update.available?.versionName.orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FirstSyncPrompt(onSync: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // **No `weight` here**, and the reason cost a golden: this block lives inside a `LazyColumn`,
        // where the parent's height is unconstrained. A child with `weight` gets its share of the
        // *remaining* space, and in a container that sizes itself to its content that share is
        // **zero**: the title and the description disappeared, leaving only the button under an empty
        // space. Whoever wants to fill the screen asks for it from outside, with
        // `fillParentMaxHeight`.
        EmptyState(
            icon = Icons.Rounded.CloudDownload,
            title = stringResource(R.string.home_first_sync_title),
            description = stringResource(R.string.home_first_sync_message),
        )
        Button(
            onClick = onSync,
            modifier = Modifier.padding(top = spacing.large),
        ) {
            Text(text = stringResource(R.string.home_first_sync_action))
        }
    }
}

@Composable
private fun SyncProgressBanner(status: IndexStatus.Syncing, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = stringResource(R.string.home_syncing_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when (val expected = status.expected) {
                    // On an incremental diff the store does not declare how many entries it is
                    // sending: a determinate bar at 60% that then jumps backwards is worse than one
                    // that spins.
                    null -> stringResource(R.string.home_syncing_processed, status.processed)
                    else -> stringResource(R.string.home_syncing_progress, status.processed, expected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
            val expected = status.expected
            if (expected != null && expected > 0) {
                LinearProgressIndicator(
                    progress = { (status.processed.toFloat() / expected).coerceIn(0f, 1f) },
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
    }
}

@Composable
private fun SyncFailureBanner(
    status: IndexStatus.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = stringResource(R.string.home_sync_failed_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = appErrorMessage(status.error),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
            Row(modifier = Modifier.padding(top = spacing.small)) {
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.home_sync_retry))
                }
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.home_sync_dismiss))
                }
            }
        }
    }
}

@Composable
private fun MeteredConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.home_metered_title)) },
        text = { Text(text = stringResource(R.string.home_metered_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.home_metered_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.home_metered_dismiss))
            }
        },
    )
}

/**
 * How many category chips fit in the Home.
 *
 * Ten and not all of them: F-Droid publishes 108. The number is not arbitrary but follows from
 * categories arriving ordered by app count — F-Droid's first ten span "System" (629 apps) to "Note"
 * (105), that is the part of the catalogue worth entering without already knowing what to look for.
 */
private const val HOME_CATEGORY_LIMIT = 10

/** How wide an index card is: two and a half per screen, so it is visible that it scrolls. */
private val INDEX_CARD_WIDTH = 96.dp
private val INDEX_CARD_ICON = 56.dp

/** Release notes are a summary, not a changelog: beyond three lines they are truncated. */
private const val SELF_UPDATE_NOTES_LINES = 3

private fun IndexStatus.entryCount(): Int? = when (this) {
    is IndexStatus.Synced -> entryCount
    is IndexStatus.Failed -> previous?.entryCount
    IndexStatus.NeverSynced, is IndexStatus.Syncing -> null
}

@Preview(name = "Home light")
@Composable
private fun HomeScreenLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) { HomePreviewContent() }
}

@Preview(name = "Home dark")
@Composable
private fun HomeScreenDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) { HomePreviewContent() }
}

@Composable
private fun HomePreviewContent() {
    HomeScreen(
        uiState = HomeUiState.Ready(
            storeId = StoreId.FDROID,
            index = IndexStatus.NeverSynced,
            recentlyUpdated = emptyList(),
        ),
        preferredLanguageTags = listOf("it"),
        onAppClick = { _, _ -> },
        onBrowseCatalogue = { _, _ -> },
        onSync = {},
        onSyncWithConsent = {},
        onDismissMeteredConsent = {},
        onDismissFailure = {},
    )
}
