package com.multistore.feature.appdetail

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multistore.core.data.repository.ListingRead
import com.multistore.core.data.repository.StoreComparison
import com.multistore.core.data.repository.StoreComparisonRow
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.model.ModifiedBuild
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.ui.component.EmptyState
import com.multistore.core.ui.component.MultiStoreDetailTopAppBar
import com.multistore.store.api.HashAvailability
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant

/**
 * The nine stores' answers about one app, side by side.
 *
 * ### Why this screen exists
 *
 * Since M5 the jump from one store's listing to another **replaces** rather than stacks — decided on
 * purpose, because comparing four stores used to leave four pages to close one at a time. The cost of
 * that decision is that comparing became something held entirely in the reader's head: go, look,
 * come back, remember. Every number needed to do it on one surface was already in `store_listings`
 * and `app_versions`; nothing here is fetched.
 *
 * ### A card per store, not a grid
 *
 * Six columns and up to nine rows do not fit across a phone, and a table that scrolls in both
 * directions is one where the row you are reading loses its heading. Each store is a card whose
 * fields are labelled, so the comparison is made by scrolling **one** axis and no cell is ever
 * orphaned from what it means. The wide layout is still available: the field rows scroll
 * horizontally inside their own card if a value is long, and nothing pushes the page sideways.
 *
 * ### The empty cells are the point
 *
 * Three states, not two, and only one of them is a statement about the store: a value, "does not
 * publish it", and "not read yet". The third is a listing cross-store matching discovered in a
 * result list and nobody has opened — writing "does not publish it" there would be a claim about a
 * source nobody has asked. It is the same discipline as `UpToDate.comparable`, and this screen is
 * where it is most visible because it is the screen made of absences.
 */
@Composable
fun StoreComparisonScreen(
    onBack: () -> Unit,
    onOpenListing: (StoreId, StoreAppRef) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StoreComparisonViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    StoreComparisonScreen(
        comparison = uiState,
        storeDisplayName = viewModel::storeDisplayName,
        onBack = onBack,
        onOpenListing = onOpenListing,
        modifier = modifier,
    )
}

/** ViewModel-free variant, for previews and screenshot tests. */
@Composable
internal fun StoreComparisonScreen(
    comparison: StoreComparison,
    storeDisplayName: (StoreId) -> String,
    onBack: () -> Unit,
    onOpenListing: (StoreId, StoreAppRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Scaffold(
        modifier = modifier,
        topBar = {
            MultiStoreDetailTopAppBar(
                // The app's name when there is one, the screen's own name before the first emission:
                // a bar that starts empty and fills in shifts the title under the reader's eye.
                title = comparison.title.ifEmpty { stringResource(R.string.comparison_title) },
                onBack = onBack,
            )
        },
    ) { padding ->
        if (!comparison.isComparable) {
            EmptyState(
                icon = Icons.AutoMirrored.Rounded.CompareArrows,
                title = stringResource(R.string.comparison_empty_title),
                description = stringResource(R.string.comparison_empty_message),
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                bottom = spacing.extraLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            items(
                items = comparison.rows,
                // `(storeId, ref)` and not the store alone: apkmirror publishes one page per
                // variant, so two rows of the same store are possible and `LazyColumn` demands two
                // different keys. It is the lesson of `AggregatedApp.listKey`, one screen further on.
                key = { "${it.storeId.wireName}/${it.ref.value}" },
            ) { row ->
                StoreCard(
                    row = row,
                    storeName = storeDisplayName(row.storeId),
                    onOpen = { onOpenListing(row.storeId, row.ref) },
                )
            }

            item {
                Footnotes(
                    unexplored = comparison.otherStoresUnexplored,
                    modifier = Modifier.padding(top = spacing.small),
                )
            }
        }
    }
}

/**
 * One store's answer: its name, then the six facts, then the way in.
 *
 * The store currently being looked at is **marked rather than left out**: "is this store behind the
 * others" is not a question a table without this store can answer. It keeps its "open" button all
 * the same — pressing it returns to the page one came from, which is what a row of a comparison
 * should do regardless of which one it is.
 */
@Composable
private fun StoreCard(
    row: StoreComparisonRow,
    storeName: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (row.current) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = storeName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (row.current) {
                    Text(
                        text = stringResource(R.string.comparison_current_marker),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.small))

            Fact(R.string.comparison_column_version, row.versionName ?: notPublished(row))
            Fact(R.string.comparison_column_updated, row.lastUpdated?.let(::formatDate) ?: notPublished(row))
            Fact(R.string.comparison_column_size, formatSize(row))
            Fact(R.string.comparison_column_hash, stringResource(row.hashAvailability.labelRes))
            Fact(
                R.string.comparison_column_package,
                // The package name itself and not a yes: it is the value step 4 of the pre-install
                // pipeline compares against, and two stores redistributing the same app under two
                // package names is a real case — uptodown ships Telegram as
                // `org.telegram.messenger.web`, apkcombo as `org.telegram.messenger`. A "yes" in
                // both cells would hide the one difference that decides an update will be refused.
                row.packageName ?: notPublished(row),
            )
            Fact(R.string.comparison_column_build, stringResource(row.modifiedBuild.comparisonLabelRes))

            TextButton(
                onClick = onOpen,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = spacing.small),
            ) {
                Text(text = stringResource(R.string.comparison_open_action, storeName))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    // Null: the button's own text already says where it leads, and a screen reader
                    // announcing both would read the destination twice.
                    contentDescription = null,
                    modifier = Modifier.padding(start = spacing.extraSmall),
                )
            }
        }
    }
}

/**
 * A labelled fact: what it is on the left, what this store says on the right.
 *
 * The value scrolls inside its own row rather than wrapping. A package name is a long unbroken
 * string, and letting it wrap would make one card three lines taller than its neighbours — which in
 * a comparison reads as a difference between the stores rather than between the strings.
 */
@Composable
private fun Fact(labelRes: Int, value: String, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.extraSmall),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

/**
 * What the table is for, said in words under it, plus what it does not know.
 *
 * The first note is the reason the last three columns are there at all: they are not trivia about
 * the sources, they are what decides how much the pre-install pipeline will be able to prove. The
 * second says how many stores have not been asked — a **count**, not a button: asking costs a request
 * to each of them, so the gesture stays on the listing where it already is.
 */
@Composable
private fun Footnotes(unexplored: Int, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(
            text = stringResource(R.string.comparison_footnote_verification),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (unexplored > 0) {
            Text(
                text = stringResource(R.string.comparison_footnote_unexplored, unexplored),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

/**
 * The two ways a cell can be empty, and they are not interchangeable.
 *
 * A listing that has been **read** and carries nothing says so about the store. One discovered in a
 * result list and never opened says nothing about the store at all — cross-store matching writes
 * those rows with `ttl_seconds = 0`, "born already expired", and the size lives in versions those
 * rows do not have. Printing the first sentence over the second case is the mistake this whole
 * screen is arranged to avoid.
 *
 * Since the table reads those rows on arrival there are two more, and neither is a variant of the
 * others: "reading" is a promise still being kept, "could not be read" is one that was broken. Both
 * are transient and neither claims anything about what the store publishes.
 */
@Composable
private fun notPublished(row: StoreComparisonRow): String = stringResource(
    when {
        // A read listing carrying nothing is the one case that says something about the store.
        row.listingRead -> R.string.comparison_not_published
        // The two states the data cannot express, and both have to stop the cell promising: one is
        // in flight, the other was asked and refused. "Not read yet" over a store that has just
        // said no would be the only false sentence on this screen.
        row.read == ListingRead.RUNNING -> R.string.comparison_reading
        row.read == ListingRead.FAILED -> R.string.comparison_read_failed
        else -> R.string.comparison_not_read_yet
    },
)

/**
 * The size, in the units the rest of the app uses.
 *
 * `Formatter.formatShortFileSize` is SI on Android, and this project has already decided that where
 * the two conventions disagree the one the user compares against wins — see the image-cache ceiling.
 */
@Composable
private fun formatSize(row: StoreComparisonRow): String {
    val context = LocalContext.current
    val bytes = row.sizeBytes ?: return notPublished(row)
    return Formatter.formatShortFileSize(context, bytes)
}

/** The device's own short date format: a comparison is read at a glance, not parsed. */
private fun formatDate(instant: Instant): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(instant.toEpochMilliseconds()))

/**
 * How often this store publishes a file hash — the adapter's declaration, not this listing's.
 *
 * It is the only column known even for a listing nobody has opened, and it is verified rather than
 * asserted: the contract test compares the declaration against how many hashes the real fixtures
 * carry. `SOMETIMES` is not a hedge — an1 publishes one on 2 of 6 sampled objects, and apkmirror on
 * single APKs but never on bundles, because there is no single file to hash.
 */
private val HashAvailability.labelRes: Int
    get() = when (this) {
        HashAvailability.ALWAYS -> R.string.comparison_hash_always
        HashAvailability.SOMETIMES -> R.string.comparison_hash_sometimes
        HashAvailability.NONE -> R.string.comparison_hash_never
    }

/**
 * The build column's three words.
 *
 * Distinct from the badge's labels on purpose: here the silent state has to **say** something,
 * because a blank cell in a column of three would read as missing data rather than as "this source
 * publishes what the developer published". The badge can stay silent because it sits in a header
 * where nothing else is claiming a value.
 */
private val ModifiedBuild.comparisonLabelRes: Int
    get() = when (this) {
        ModifiedBuild.NONE -> R.string.comparison_build_original
        ModifiedBuild.POSSIBLE -> R.string.comparison_build_possible
        ModifiedBuild.DECLARED -> R.string.comparison_build_declared
    }

/** Wide enough for the longest of the six German labels, so the values line up down the card. */
private val LABEL_WIDTH = 148.dp
