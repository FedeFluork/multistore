package com.multistore.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multistore.core.common.net.StoreDiagnosis
import com.multistore.core.common.text.TextNormalizer
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.model.StoreCategory
import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId
import com.multistore.core.ui.component.EmptyState
import com.multistore.core.ui.component.MultiStoreDetailTopAppBar
import com.multistore.core.ui.component.StoreDiagnosisDialog

/**
 * Which stores MultiStore searches, on a screen of its own.
 *
 * ### Why it stopped being a dialog
 *
 * Nine stores with their descriptions already did not fit the height a dialog has on a phone — the
 * old one scrolled inside an `AlertDialog`, which is the shape of a control that has outgrown its
 * container. With more sources ahead, the three things this screen now has are the things a dialog
 * could not hold: tabs, a search field, and a way to act on a whole group at once.
 *
 * ### It writes as you tap
 *
 * The dialog it replaces batched, and had a reason to: each write touches a Room row and makes the
 * flow search observes re-emit, so saving nine unchanged rows was nine rewrites for nothing. That
 * reason survives as [StoreChooserViewModel.setAll], which writes **only what changed** — but the
 * batching itself does not. A screen with a Save button is a screen that can be left without its
 * changes, and a card that highlights the instant it is tapped has already told the reader the
 * write happened.
 */
@Composable
fun StoreChooserScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StoreChooserViewModel = hiltViewModel(),
) {
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    StoreChooserScreen(
        stores = stores,
        onBack = onBack,
        onSetEnabled = viewModel::setEnabled,
        onSetAll = viewModel::setAll,
        onStoreDiagnosis = viewModel::storeDiagnosis,
        modifier = modifier,
    )
}

/** ViewModel-free variant, for previews and screenshot tests. */
@Composable
internal fun StoreChooserScreen(
    stores: List<StoreEntry>,
    onBack: () -> Unit,
    onSetEnabled: (StoreId, Boolean) -> Unit,
    onSetAll: (List<StoreId>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onStoreDiagnosis: suspend (StoreId) -> StoreDiagnosis? = { null },
    initialTab: StoreTab = StoreTab.ALL,
) {
    val spacing = LocalSpacing.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Both survive process death, and neither belongs in the ViewModel: which tab is open and what
    // has been typed into a filter describe the **screen**, not the app. The rule is the one written
    // for the search list's anchor — before putting UI state in `remember`, ask whether it describes
    // the composition or the person.
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }

    var explaining by rememberSaveable { mutableStateOf<StoreId?>(null) }
    var diagnosis by remember { mutableStateOf<StoreDiagnosis?>(null) }
    LaunchedEffect(explaining) {
        diagnosis = explaining?.let { onStoreDiagnosis(it) }
    }

    // The tab decides the group, the field narrows what is drawn **inside** it. They are not the
    // same filter and must not be merged: the counts on the tabs are the group's, so a search that
    // also changed them would make "Modified 2/5" mean "2 of the 5 whose name contains what I
    // typed" — a number nobody asked for, in the place a guarantee is read.
    val inTab = stores.filter { tab.holds(it.category) }
    val visible = inTab.filter { it.matches(query) }

    Scaffold(
        modifier = modifier,
        topBar = {
            MultiStoreDetailTopAppBar(
                title = stringResource(SettingsSection.STORES.titleRes),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(text = stringResource(R.string.settings_stores_search_label)) },
                leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.settings_stores_search_clear),
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

            // Scrollable rather than fixed: four tabs whose labels carry a count already crowd a
            // phone in German, and a fixed row would truncate the count — which is the half of the
            // label that cannot be guessed from the other half.
            ScrollableTabRow(
                selectedTabIndex = StoreTab.entries.indexOf(tab),
                edgePadding = spacing.screenHorizontal,
            ) {
                StoreTab.entries.forEach { candidate ->
                    val group = stores.filter { candidate.holds(it.category) }
                    Tab(
                        selected = candidate == tab,
                        onClick = { tab = candidate },
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.settings_stores_tab_label,
                                    stringResource(candidate.titleRes),
                                    group.count { it.enabled },
                                    group.size,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            SelectAllRow(
                stores = inTab,
                onSetAll = onSetAll,
                modifier = Modifier.padding(
                    start = spacing.screenHorizontal,
                    end = spacing.screenHorizontal,
                    top = spacing.small,
                ),
            )

            if (visible.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.settings_stores_search_empty_title),
                    description = stringResource(R.string.settings_stores_search_empty_message),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = spacing.screenHorizontal,
                        end = spacing.screenHorizontal,
                        top = spacing.small,
                        bottom = spacing.large,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                    modifier = Modifier.weight(1f),
                ) {
                    items(items = visible, key = { it.storeId.wireName }) { store ->
                        StoreChoiceCard(
                            store = store,
                            onToggle = { onSetEnabled(store.storeId, !store.enabled) },
                            onExplain = { explaining = store.storeId },
                        )
                    }
                }
            }

            // The total, and it is the whole list's rather than the open tab's — the tab already
            // carries its own in its label, and repeating it underneath would be the same number
            // twice while the question this line answers ("how many am I searching?") went unasked.
            Text(
                text = stringResource(
                    R.string.settings_stores_active_count,
                    stores.count { it.enabled },
                    stores.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = spacing.screenHorizontal,
                    end = spacing.screenHorizontal,
                    bottom = spacing.large,
                ),
            )
        }
    }

    diagnosis?.let { current ->
        StoreDiagnosisDialog(
            diagnosis = current,
            storeName = stores.firstOrNull { it.storeId == current.storeId }?.displayName
                ?: current.storeId.wireName,
            onDismiss = {
                explaining = null
                diagnosis = null
            },
        )
    }
}

/**
 * "Everything in this tab", as one gesture.
 *
 * ### Tri-state, because two states would lie
 *
 * A plain checkbox has to claim either "all of these are on" or "none are", and the ordinary case is
 * neither. `TriStateCheckbox` would be the literal control for it; a `Checkbox` is used instead with
 * the indeterminate case rendered as unchecked-and-labelled, because the **label** carries the count
 * anyway and two ways of saying the same number is one more than needed.
 *
 * What it writes is scoped to the tab and not to the catalogue. On "All" the two are the same list,
 * which is the point of that tab existing.
 */
@Composable
private fun SelectAllRow(
    stores: List<StoreEntry>,
    onSetAll: (List<StoreId>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stores.isEmpty()) return
    val spacing = LocalSpacing.current
    val allOn = stores.all { it.enabled }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Checkbox(
            checked = allOn,
            // Turning them all off is allowed, and deliberately so: a search with no store answers
            // nothing, which is a state the user can see and undo in one tap. Forbidding it would be
            // a control that refuses without explaining, on a screen whose whole subject is choice.
            onCheckedChange = { onSetAll(stores.map { it.storeId }, !allOn) },
        )
        Text(
            text = stringResource(
                if (allOn) R.string.settings_stores_deselect_all else R.string.settings_stores_select_all,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One store, as a card the whole of which is the switch.
 *
 * ### The selected state is the card, not a control on it
 *
 * A switch on the right made the row's own 300 horizontal points inert: the description explaining
 * what to expect from that source was the largest target on screen and did nothing. Here the state
 * is drawn as the card's **border and fill**, which is what the eye scans first when the question is
 * "which of these are on", and every point of it is the gesture.
 *
 * Both signals and not one: a tinted background alone is a contrast difference, which is exactly
 * what a reader who cannot rely on colour loses. The border carries the same fact in shape, and
 * `toggleable` gives the screen reader the state in words.
 */
@Composable
private fun StoreChoiceCard(
    store: StoreEntry,
    onToggle: () -> Unit,
    onExplain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val description = storeDescriptionRes(store.storeId)?.let { stringResource(it) } ?: store.host
    Surface(
        checked = store.enabled,
        onCheckedChange = { onToggle() },
        shape = MaterialTheme.shapes.medium,
        color = if (store.enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (store.enabled) {
            BorderStroke(
                width = SELECTED_BORDER,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            null
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = store.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.extraSmall),
            )
            // Shown only when it is not the normal state, as in the list it replaces: an "all fine"
            // line under every store would be noise, and would make the one that matters less
            // visible on the day it appears.
            storeStateRes(store.health.state)?.let { stateRes ->
                Text(
                    text = stringResource(stateRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    // Its own tap, inside a card that is itself a toggle. Without it, tapping
                    // "fails since Tuesday" would **also** switch the store off, which is the
                    // opposite of what somebody reading a fault wants; Compose gives the gesture to
                    // the innermost clickable, so the card keeps every other point of itself.
                    modifier = Modifier
                        .padding(top = spacing.small)
                        .clickable(onClick = onExplain),
                )
            }
        }
    }
}


/**
 * The four tabs, in the order they are drawn.
 *
 * `ALL` is first and is the one that opens, because it is the only one that answers "what is there"
 * without the reader having to know the taxonomy first.
 */
enum class StoreTab(@StringRes val titleRes: Int) {
    ALL(R.string.settings_stores_tab_all),
    OPEN_SOURCE(R.string.settings_stores_tab_open_source),
    ORIGINAL(R.string.settings_stores_tab_original),
    MODIFIED(R.string.settings_stores_tab_modified),
    ;

    /** Whether a store of this category is drawn under this tab. */
    fun holds(category: StoreCategory): Boolean = when (this) {
        ALL -> true
        OPEN_SOURCE -> category == StoreCategory.OPEN_SOURCE
        ORIGINAL -> category == StoreCategory.ORIGINAL
        MODIFIED -> category == StoreCategory.MODIFIED
    }
}

/**
 * Whether the typed text finds this store.
 *
 * Normalised on both sides with the same function the catalogue uses, so "pdalife" finds PDALIFE and
 * an accent typed or omitted makes no difference. The host is searched too: it is what somebody who
 * knows the site by its address will type, and on a store whose display name differs from its domain
 * it is the only thing they have.
 */
private fun StoreEntry.matches(query: String): Boolean {
    val needle = TextNormalizer.normalizeTitle(query)
    if (needle.isEmpty()) return true
    return TextNormalizer.normalizeTitle(displayName).contains(needle) ||
        TextNormalizer.normalizeTitle(host).contains(needle)
}

/** Thick enough to read as a border rather than as an edge artefact, in both themes. */
private val SELECTED_BORDER = 2.dp
