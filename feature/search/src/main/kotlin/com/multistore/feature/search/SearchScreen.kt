package com.multistore.feature.search

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multistore.core.data.repository.StoreShortfall
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.ContentKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.ResultOrigin
import com.multistore.core.model.SearchSort
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.ui.component.AppListItem
import com.multistore.core.ui.component.EmptyState
import com.multistore.core.ui.component.MultiStoreTopAppBar
import com.multistore.core.ui.component.appErrorMessage
import com.multistore.core.ui.rememberPreferredLanguageTags
import com.multistore.store.api.FilterCapability

/**
 * Search screen.
 *
 * On F-Droid the search does not touch the network: it reads the already-synced index. That is also
 * why the debounce lives in the ViewModel and not here — the screen must not need to know what a
 * search costs, but the ViewModel does, because for the other eight stores it costs an HTTP request.
 */
@Composable
fun SearchScreen(
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    SearchScreen(
        uiState = uiState,
        filters = filters,
        preferredLanguageTags = rememberPreferredLanguageTags(),
        storeDisplayName = viewModel::storeDisplayName,
        onQueryChange = viewModel::onQueryChange,
        onAppClick = onAppClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onContentKindChange = viewModel::setContentKind,
        onMinRatingChange = viewModel::setMinRating,
        onSortChange = viewModel::setSort,
        onStoreToggle = viewModel::toggleStore,
        onResetFilters = { viewModel.resetFilters() },
        modifier = modifier,
    )
}

/** ViewModel-free variant, for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(
    uiState: SearchUiState,
    preferredLanguageTags: List<String>,
    storeDisplayName: (StoreId) -> String,
    onQueryChange: (String) -> Unit,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    filters: SearchFilterState = SearchFilterState(),
    onContentKindChange: (ContentKind?) -> Unit = {},
    onMinRatingChange: (Float?) -> Unit = {},
    onSortChange: (SearchSort) -> Unit = {},
    onStoreToggle: (StoreId, Boolean) -> Unit = { _, _ -> },
    onResetFilters: () -> Unit = {},
    /** For the goldens: the panel is a state of the screen, not a variant. */
    initiallyShowingFilters: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val keyboard = LocalSoftwareKeyboardController.current
    var showFilters by rememberSaveable { mutableStateOf(initiallyShowingFilters) }

    Scaffold(
        modifier = modifier,
        topBar = { MultiStoreTopAppBar(title = stringResource(R.string.search_title)) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text(text = stringResource(R.string.search_field_label)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.search_field_clear),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal, vertical = spacing.small),
            )

            FilterBar(
                filters = filters,
                onOpen = { showFilters = true },
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal),
            )

            when (uiState) {
                is SearchUiState.Idle -> EmptyState(
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.search_idle_title),
                    description = stringResource(R.string.search_idle_message),
                    modifier = Modifier.weight(1f),
                )

                is SearchUiState.Searching -> Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    CircularProgressIndicator()
                    if (uiState.queried > 0) {
                        Text(
                            text = stringResource(
                                R.string.search_progress_stores,
                                uiState.answered,
                                uiState.queried,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = spacing.large),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                is SearchUiState.NoResults -> Column(modifier = Modifier.weight(1f)) {
                    FilteredOutBanner(uiState.shortfalls, storeDisplayName)
                    ShortfallBanner(uiState.shortfalls, storeDisplayName, onRetry)
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = stringResource(R.string.search_no_results_title, uiState.query),
                        description = stringResource(R.string.search_no_results_message),
                    )
                }

                is SearchUiState.Results -> Column(modifier = Modifier.weight(1f)) {
                    FilteredOutBanner(uiState.shortfalls, storeDisplayName)
                    ShortfallBanner(uiState.shortfalls, storeDisplayName, onRetry)
                    if (uiState.stillArriving) {
                        InfoBanner(
                            text = stringResource(
                                R.string.search_progress_still_arriving,
                                uiState.answered,
                                uiState.queried,
                            ),
                        )
                    }
                    if (uiState.partialByBootstrap) {
                        InfoBanner(text = stringResource(R.string.search_bootstrap_notice))
                    }
                    ResultList(
                        state = uiState,
                        preferredLanguageTags = preferredLanguageTags,
                        storeDisplayName = storeDisplayName,
                        onAppClick = onAppClick,
                        onLoadMore = onLoadMore,
                    )
                }
            }
        }
    }

    if (showFilters) {
        SearchFilterSheet(
            filters = filters,
            onContentKindChange = onContentKindChange,
            onMinRatingChange = onMinRatingChange,
            onSortChange = onSortChange,
            onStoreToggle = onStoreToggle,
            onReset = onResetFilters,
            onDismiss = { showFilters = false },
        )
    }
}

/**
 * The row under the field: how many filters are active, and how to open them.
 *
 * The count does not include the sort order, the same choice as [SearchFilterState.activeCount]: a
 * sort order hides nothing, and flagging it as an active filter would send people looking for results
 * that have not gone anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    filters: SearchFilterState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = filters.activeCount > 0,
            onClick = onOpen,
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.FilterList, contentDescription = null)
            },
            label = {
                Text(
                    text = if (filters.activeCount == 0) {
                        stringResource(R.string.search_filters_open)
                    } else {
                        pluralStringResource(
                            R.plurals.search_filters_active,
                            filters.activeCount,
                            filters.activeCount,
                        )
                    },
                )
            },
        )
        if (filters.sort != SearchSort.RELEVANCE) {
            Text(
                text = stringResource(sortLabel(filters.sort)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = LocalSpacing.current.small),
            )
        }
    }
}

/**
 * The stores left out **by the question**, not by a fault.
 *
 * It gets a notice of its own rather than sitting among the other shortfalls because the remedy is
 * different: there one retries, here one removes a filter. A "Search again" button above this list
 * would knock on the same door for the same silence, and it would be the only thing the user could
 * think to do.
 */
@Composable
private fun FilteredOutBanner(
    shortfalls: List<StoreShortfall>,
    storeDisplayName: (StoreId) -> String,
    modifier: Modifier = Modifier,
) {
    val filtered = shortfalls.filter { it.unsupportedFilters.isNotEmpty() }
    if (filtered.isEmpty()) return
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = stringResource(R.string.search_filtered_out_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Grouped by **reason**, not one per store. With a single filter active the excluded are
            // seven out of nine, and seven rows repeating the same sentence with a different name are
            // a wall nobody reads — seen on the emulator. One row per reason says the same two things
            // (who, and why) and reads.
            filtered.groupBy { it.unsupportedFilters }.forEach { (missing, stores) ->
                val names = stores.map { storeDisplayName(it.storeId) }.joinToString(separator = ", ")
                // The strings are resolved **before** composing them: `stringResource` is a composable
                // and cannot be called inside `joinToString`'s lambda.
                val reasons = missing.map { stringResource(filterName(it)) }
                    .joinToString(separator = ", ")
                Text(
                    text = pluralStringResource(
                        R.plurals.search_filtered_out_entry,
                        stores.size,
                        names,
                        reasons,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.extraSmall),
                )
            }
        }
    }
}

@StringRes
private fun filterName(capability: FilterCapability): Int = when (capability) {
    FilterCapability.CONTENT_KIND -> R.string.search_filter_name_content_kind
    FilterCapability.MIN_RATING -> R.string.search_filter_name_min_rating
    // The others are not offered by the panel, so they cannot appear here. Falling back to category is
    // the least wrong option: it is the only one of the rest that describes a field of a list row.
    else -> R.string.search_filter_name_category
}

@Composable
private fun ResultList(
    state: SearchUiState.Results,
    preferredLanguageTags: List<String>,
    storeDisplayName: (StoreId) -> String,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(
            items = state.apps,
            // `listKey` and not `appKey`: the group's key is the **domain** identity and is not unique
            // by construction — two groups the matcher kept apart can share it if they have the same
            // normalised title and neither publishes the package name. `LazyColumn` demands uniqueness,
            // and without it **it crashes**: that happened when an1 was added, which never publishes the
            // `packageName`. `listKey` appends the listing that represents the group, which is unique,
            // and stays stable across two recompositions — so the row moves rather than disappearing and
            // reappearing when a late store joins the group.
            key = { it.listKey },
        ) { app ->
            val primary = app.primary
            AppListItem(
                summary = app.displaySummary,
                preferredLanguageTags = preferredLanguageTags,
                onClick = { onAppClick(primary.storeId, primary.ref) },
                supporting = {
                    Column {
                        StoreProvenance(app = app, storeDisplayName = storeDisplayName)
                        // The rating is shown **because one can sort by rating**: a list reordered by a
                        // number that appears nowhere is a list that looks randomly shuffled. Four stores
                        // out of nine publish it, so a row without a rating is the normal case and not an
                        // error.
                        app.displaySummary.rating?.let { rating ->
                            Text(
                                text = stringResource(R.string.search_rating, formatRating(rating)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
        if (state.hasMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(LocalSpacing.current.large),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator()
                    } else {
                        TextButton(onClick = onLoadMore) {
                            Text(text = stringResource(R.string.search_load_more))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where this row comes from: one store, or several.
 *
 * A group's row shows **the number** and the names, not just the number: "available on 3 stores" says
 * there is a choice, "F-Droid, apkcombo, APKMirror" says which — and among the nine stores the
 * difference between one that publishes a hash and one that redistributes modified builds is exactly
 * what a person decides on.
 */
@Composable
private fun StoreProvenance(
    app: AggregatedApp,
    storeDisplayName: (StoreId) -> String,
    modifier: Modifier = Modifier,
) {
    val names = app.stores.joinToString(separator = ", ", transform = storeDisplayName)
    Text(
        text = if (app.storeCount == 1) {
            names
        } else {
            pluralStringResource(R.plurals.search_available_on_stores, app.storeCount, app.storeCount) +
                " · " + names
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Which sources are missing, and why.
 *
 * A list that does not say so makes an incomplete result look complete. An open breaker gets a sentence
 * of its own because it is not a fault happening now — it is the consequence of past faults, and "we
 * did not even try" is different information from "we tried and got no answer".
 */
@Composable
private fun ShortfallBanner(
    shortfalls: List<StoreShortfall>,
    storeDisplayName: (StoreId) -> String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Whoever was excluded by the filters already has their own notice, with its own explanation and
    // without the "Search again" button. Without this line they would appear twice, and the second time
    // it would say "no answer" about a store nothing was asked of — the more wrong of the two sentences.
    // Seen on the golden before the fix.
    val failures = shortfalls.filter { it.unsupportedFilters.isEmpty() }
    if (failures.isEmpty()) return
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = stringResource(R.string.search_shortfall_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            failures.forEach { shortfall ->
                val name = storeDisplayName(shortfall.storeId)
                val detail = when {
                    shortfall.circuitOpen && shortfall.retryIn != null -> {
                        val minutes = shortfall.retryIn!!.inWholeMinutes.toInt().coerceAtLeast(1)
                        pluralStringResource(
                            R.plurals.search_shortfall_paused_retry,
                            minutes,
                            minutes,
                        )
                    }

                    shortfall.circuitOpen -> stringResource(R.string.search_shortfall_paused)
                    shortfall.partial -> stringResource(R.string.search_shortfall_partial)
                    shortfall.error != null -> appErrorMessage(shortfall.error!!)
                    else -> stringResource(R.string.search_shortfall_unknown)
                }
                Text(
                    text = stringResource(R.string.search_shortfall_entry, name, detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.extraSmall),
                )
            }
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = spacing.extraSmall),
            ) {
                Text(text = stringResource(R.string.search_shortfall_retry))
            }
        }
    }
}

@Composable
private fun InfoBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(LocalSpacing.current.large),
        )
    }
}

@Preview(name = "Search light")
@Composable
private fun SearchScreenLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) { SearchPreviewContent() }
}

@Preview(name = "Search dark")
@Composable
private fun SearchScreenDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) { SearchPreviewContent() }
}

@Composable
private fun SearchPreviewContent() {
    SearchScreen(
        uiState = SearchUiState.Results(
            query = "browser",
            apps = listOf(
                AggregatedApp(
                    appKey = "pkg:org.mozilla.fennec_fdroid",
                    listings = listOf(
                        AggregatedListing(
                            summary = StoreListingSummary(
                                storeId = StoreId.FDROID,
                                ref = StoreAppRef("org.mozilla.fennec_fdroid"),
                                title = "Fennec",
                                summary = LocalizedText(
                                    mapOf("en" to "A Firefox build without proprietary bits."),
                                ),
                                latestVersionName = "128.0",
                            ),
                            origin = ResultOrigin.LOCAL_INDEX,
                        ),
                    ),
                ),
            ),
            shortfalls = emptyList(),
            hasMore = false,
        ),
        preferredLanguageTags = listOf("en"),
        storeDisplayName = { it.wireName },
        onQueryChange = {},
        onAppClick = { _, _ -> },
        onLoadMore = {},
        onRetry = {},
    )
}
