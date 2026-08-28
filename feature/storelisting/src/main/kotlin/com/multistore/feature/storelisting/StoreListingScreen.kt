package com.multistore.feature.storelisting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.Category
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.ui.component.AppListItem
import com.multistore.core.ui.component.EmptyState
import com.multistore.core.ui.component.MultiStoreDetailTopAppBar
import com.multistore.core.ui.rememberPreferredLanguageTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Browsing a store's catalogue.
 *
 * It is search's counterpart: search serves whoever already knows what they want, this serves whoever
 * does not. With a local index of 4,269 packages the second group is the majority, and until now it had
 * no route at all.
 *
 * Category names do **not** live in `strings.xml`: the store publishes them, already localised (F-Droid
 * in about a hundred languages). Putting them among our strings would mean hand-translating into five
 * languages a vocabulary that arrives over the network and changes without us.
 */
@Composable
fun StoreListingScreen(
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StoreListingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    StoreListingScreen(
        uiState = uiState,
        apps = viewModel.apps,
        preferredLanguageTags = rememberPreferredLanguageTags(),
        onAppClick = onAppClick,
        onSelectCategory = viewModel::selectCategory,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * ViewModel-free variant, for previews and screenshot tests.
 *
 * The apps arrive as a `Flow<PagingData<…>>` and not as a list: that is the type the ViewModel exposes,
 * and taking a different one would mean the golden draws something nobody draws in production. In tests
 * it is built with `flowOf(PagingData.from(rows))`.
 */
@Composable
internal fun StoreListingScreen(
    uiState: StoreListingUiState,
    apps: Flow<PagingData<StoreListingSummary>>,
    preferredLanguageTags: List<String>,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = apps.collectAsLazyPagingItems()
    Scaffold(
        modifier = modifier,
        topBar = {
            MultiStoreDetailTopAppBar(title = uiState.storeName, onBack = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            CategoryPicker(
                categories = uiState.categories,
                selectedCategoryId = uiState.selectedCategoryId,
                preferredLanguageTags = preferredLanguageTags,
                onSelectCategory = onSelectCategory,
            )

            // The three states are declared by Paging and no longer by us: `refresh` in flight, zero rows
            // once loading has finished, or the list. The difference that matters is the second —
            // `itemCount == 0` on its own would say "empty catalogue" even in the first instant, that is
            // a "no apps" notice flashing before every list.
            when {
                items.loadState.refresh is LoadState.Loading -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                items.itemCount == 0 -> EmptyState(
                    icon = Icons.Rounded.Category,
                    title = stringResource(R.string.storelisting_empty_title),
                    description = stringResource(R.string.storelisting_empty_message),
                    modifier = Modifier.weight(1f),
                )

                else -> AppList(
                    items = items,
                    preferredLanguageTags = preferredLanguageTags,
                    onAppClick = onAppClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The category picker.
 *
 * A menu rather than a row of chips, and the reason is measured: **F-Droid publishes 108 categories**.
 * A hundred and eight chips laid out horizontally are a scroll nobody reaches the end of, and truncating
 * to ten would leave the other ninety-eight unreachable. A menu keeps them all, scrolls vertically — the
 * direction in which a list is read — and shows next to each how many apps it contains, which is the only
 * information that helps pick one out of many.
 *
 * The categories arrive already ordered by app count: alphabetically the first would be "AI Chat" and
 * "Alarm Clock" instead of "System" and "Internet".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    categories: List<Category>,
    selectedCategoryId: String?,
    preferredLanguageTags: List<String>,
    onSelectCategory: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedCategoryId }

    Box(
        modifier = modifier.padding(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.small,
            bottom = spacing.small,
        ),
    ) {
        FilterChip(
            selected = selectedCategoryId != null,
            onClick = { expanded = true },
            label = {
                Text(
                    text = selected?.displayName(preferredLanguageTags)
                        ?: stringResource(R.string.storelisting_all_categories),
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = stringResource(R.string.storelisting_choose_category),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.storelisting_all_categories)) },
                onClick = {
                    expanded = false
                    onSelectCategory(null)
                },
            )
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(text = category.displayName(preferredLanguageTags)) },
                    trailingIcon = {
                        Text(
                            text = pluralStringResource(
                                R.plurals.storelisting_category_app_count,
                                category.appCount,
                                category.appCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectCategory(category.id)
                    },
                )
            }
        }
    }
}

/**
 * The list, served by Paging.
 *
 * The "load more" button is gone, and it is not a loss. It used to be at the bottom because pagination was
 * manual and somebody had to ask for it. Paging loads the next page when the scroll gets close to the end,
 * so the button would be one more gesture for something that has already happened. In its place there is a
 * spinner, which appears **only** if loading is slower than scrolling — on a local index, almost never.
 *
 * The key is Paging's: `itemKey` instead of `items(key = …)`, because with placeholders disabled the
 * function receives the index, and the row at that index can be `null` while a page arrives. `itemKey` is
 * the form that handles that case — and the key stays the domain one, `store/ref`, which on this list
 * **is** unique: it is a list of listings from a single store, not of cross-store groups. The rule —
 * before using a domain identifier as a list key, verify it is unique *in that list* — is here satisfied
 * by the primary key of `store_listings`.
 */
@Composable
private fun AppList(
    items: LazyPagingItems<StoreListingSummary>,
    preferredLanguageTags: List<String>,
    onAppClick: (StoreId, StoreAppRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(
            count = items.itemCount,
            key = items.itemKey { "${it.storeId.wireName}/${it.ref.value}" },
        ) { index ->
            val app = items[index] ?: return@items
            AppListItem(
                summary = app,
                preferredLanguageTags = preferredLanguageTags,
                onClick = { onAppClick(app.storeId, app.ref) },
            )
        }
        if (items.loadState.append is LoadState.Loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(LocalSpacing.current.large),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Preview(name = "StoreListing light")
@Composable
private fun StoreListingScreenLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) { StoreListingPreviewContent() }
}

@Preview(name = "StoreListing dark")
@Composable
private fun StoreListingScreenDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) { StoreListingPreviewContent() }
}

@Composable
private fun StoreListingPreviewContent() {
    StoreListingScreen(
        uiState = StoreListingUiState(
            storeName = "F-Droid",
            categories = listOf(
                Category(id = "Internet", name = LocalizedText(mapOf("it" to "Internet"))),
                Category(id = "Multimedia", name = LocalizedText(mapOf("it" to "Multimedia"))),
            ),
            selectedCategoryId = "Internet",
        ),
        apps = flowOf(
            PagingData.from(
                listOf(
                    StoreListingSummary(
                        storeId = StoreId.FDROID,
                        ref = StoreAppRef("org.mozilla.fennec_fdroid"),
                        title = "Fennec",
                        summary = LocalizedText(
                            mapOf("en" to "A build of Firefox with no proprietary bits."),
                        ),
                    ),
                ),
            ),
        ),
        preferredLanguageTags = listOf("it"),
        onAppClick = { _, _ -> },
        onSelectCategory = {},
        onBack = {},
    )
}
