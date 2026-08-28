package com.multistore.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.model.ContentKind
import com.multistore.core.model.SearchSort
import com.multistore.core.model.StoreId
import java.text.NumberFormat

/**
 * The filter panel.
 *
 * Four filters, and the census of the search fixtures explains why not seven — **a filter exists if
 * somebody publishes the field**:
 *
 *  - **size, min SDK and ABI** do not exist in `StoreListingSummary` and none of the nine
 *    `*SearchParser`s populate them. They live in `AppVersion`, that is on the detail page, which a
 *    search does not download: filtering on them would mean nine requests per row;
 *  - **category** exists on two stores — apkcombo and pdalife — plus F-Droid's index, and the three
 *    vocabularies **do not intersect**: F-Droid publishes "App Store & Updater", "Keyboard & IME",
 *    "Pass Wallet"; apkcombo the Play categories; pdalife game genres ("Arcade", "Horror"). A single
 *    list of categories would be one in which every entry means something different depending on who
 *    answers. Category stays where it is already useful and coherent: browsing one store's catalogue;
 *  - what remains is **kind**, **minimum rating**, **sort order** and **stores**.
 *
 * The panel also says which stores are left out. With a filter active, whoever cannot apply it is not
 * queried: that is `FilterPlan`'s third tier, and without writing it the only symptom would be a
 * search returning fewer results for no apparent reason. The count is in the panel because that is
 * where the decision is made — reading it only afterwards, under the results, would mean discovering
 * it once the search is done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterSheet(
    filters: SearchFilterState,
    onContentKindChange: (ContentKind?) -> Unit,
    onMinRatingChange: (Float?) -> Unit,
    onSortChange: (SearchSort) -> Unit,
    onStoreToggle: (StoreId, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        // The drag handle, as wide as it is tall.
        //
        // The stock one is 32dp x 48dp: Compose gives it the minimum height the guidelines ask for a
        // target, but not the width. The accessibility check on this screen's golden measured it, and
        // it is the only thing that check found across ten captures. Not a cosmetic detail: the handle
        // carries the expand/collapse action, so it is a real target for whoever taps imprecisely.
        //
        // **The constraint propagates to the drawing too**: the bar goes from 32dp to 48dp, and the
        // re-recorded golden shows it. The minimum constraint crosses the `Surface` down to the inner
        // `Box`, so there is no way of widening only the touch area without rewriting Material's
        // component. Sixteen density-independent pixels of extra bar are a price worth paying; a
        // hand-rewritten handle to save them is not.
        dragHandle = { BottomSheetDefaults.DragHandle(modifier = Modifier.sizeIn(minWidth = 48.dp)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal)
                .padding(bottom = spacing.extraLarge),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.search_filters_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onReset) {
                    Text(text = stringResource(R.string.search_filters_reset))
                }
            }

            FilterGroup(label = stringResource(R.string.search_filters_kind_label)) {
                // `null` first: it is the value that removes nothing and excludes no store, so it is
                // also the first one read on the way back.
                KIND_CHOICES.forEach { choice ->
                    FilterChip(
                        selected = filters.contentKind == choice,
                        onClick = { onContentKindChange(choice) },
                        label = { Text(text = stringResource(kindLabel(choice))) },
                    )
                }
            }

            FilterGroup(label = stringResource(R.string.search_filters_rating_label)) {
                FilterChip(
                    selected = filters.minRating == null,
                    onClick = { onMinRatingChange(null) },
                    label = { Text(text = stringResource(R.string.search_filters_rating_any)) },
                )
                RATING_CHOICES.forEach { choice ->
                    FilterChip(
                        selected = filters.minRating == choice,
                        onClick = { onMinRatingChange(choice) },
                        label = {
                            Text(
                                text = stringResource(
                                    R.string.search_filters_rating_value,
                                    formatRating(choice),
                                ),
                            )
                        },
                    )
                }
            }

            FilterGroup(label = stringResource(R.string.search_filters_sort_label)) {
                SearchSort.SELECTABLE.forEach { choice ->
                    FilterChip(
                        selected = filters.sort == choice,
                        onClick = { onSortChange(choice) },
                        label = { Text(text = stringResource(sortLabel(choice))) },
                    )
                }
            }

            FilterGroup(label = stringResource(R.string.search_filters_stores_label)) {
                filters.available.forEach { store ->
                    val included = store.storeId !in filters.excludedStores
                    FilterChip(
                        selected = included,
                        onClick = { onStoreToggle(store.storeId, !included) },
                        // The store name is not interface text and is not translated: the adapter
                        // declares it. See `StoreEntry.displayName`.
                        label = { Text(text = store.displayName) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.search_filters_stores_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = spacing.small),
            ) {
                Text(text = stringResource(R.string.search_filters_close))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(label: String, content: @Composable () -> Unit) {
    val spacing = LocalSpacing.current
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = spacing.large, bottom = spacing.extraSmall),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.small)) { content() }
}

@androidx.annotation.StringRes
internal fun kindLabel(kind: ContentKind?): Int = when (kind) {
    ContentKind.APP -> R.string.search_filters_kind_apps
    ContentKind.GAME -> R.string.search_filters_kind_games
    else -> R.string.search_filters_kind_all
}

@androidx.annotation.StringRes
internal fun sortLabel(sort: SearchSort): Int = when (sort) {
    SearchSort.NAME -> R.string.search_filters_sort_name
    SearchSort.RATING -> R.string.search_filters_sort_rating
    else -> R.string.search_filters_sort_relevance
}

/**
 * The rating with the current language's decimal separator.
 *
 * `NumberFormat` rather than `"%.1f"`: in Italian and French the decimal separator is a comma, and
 * "4.5" printed with a dot is the kind of detail that makes an app look untranslated.
 */
internal fun formatRating(value: Float): String =
    NumberFormat.getInstance().apply { maximumFractionDigits = 1 }.format(value)

/** "Everything", apps, games. The order is the Settings dialog's, on purpose. */
private val KIND_CHOICES = listOf(null, ContentKind.APP, ContentKind.GAME)

/**
 * The three thresholds offered.
 *
 * There is no continuous slider: the ratings of the four stores that publish them sit almost entirely
 * between 3 and 5 — on the fixtures the minimum is 0.5 and the median above 3.7 — so a slider would
 * give twenty positions of which three are useful. Three thresholds are also three fewer taps.
 */
private val RATING_CHOICES = listOf(3f, 4f, 4.5f)
