package com.multistore.core.data.repository

import com.multistore.core.model.ModifiedBuild
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.store.api.HashAvailability
import kotlin.time.Instant

/**
 * One store's answer about this app, on the row of a comparison.
 *
 * ### Why these six columns and not others
 *
 * They are the ones that differ between two stores publishing the same app, and that a person
 * actually decides on. Version and date say whether a source is behind; size says what it costs;
 * and the last three say what the pre-install pipeline will be **able to prove** — a published hash
 * makes step 2 real, a declared `packageName` makes step 4 possible, and a rework has no original
 * developer signature to compare against at step 5. Until now that comparison meant going to a store
 * and coming back, remembering: since M5 the jump between listings **replaces** rather than stacks,
 * on purpose, which made it a comparison held entirely in the reader's head.
 *
 * ### Three of the cells can be empty for two different reasons, and the reasons are not the same
 *
 * [listingRead] is what separates them. A row that came from a **result list** — cross-store matching
 * writes those with `ttl_seconds = 0`, "born already expired" — has never been read as a listing, so
 * it has no versions and therefore no size. Printing "this store does not publish it" there would be
 * a statement about the store when the truth is that nobody has asked it yet, and the whole point of
 * this screen is that its empty cells are honest. It is the same distinction as
 * `UpToDate.comparable`, one screen further out.
 *
 * [hashAvailability] is the exception on purpose: it is not read off this listing at all but declared
 * by the adapter, so it is known for every row including one nobody has opened — and it is verified,
 * because the contract test compares the declaration against how many hashes the fixtures really
 * carry.
 */
data class StoreComparisonRow(
    val storeId: StoreId,
    val ref: StoreAppRef,
    /** `true` for the listing the reader arrived from: the table marks it instead of hiding it. */
    val current: Boolean,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val lastUpdated: Instant? = null,
    val sizeBytes: Long? = null,
    val hashAvailability: HashAvailability = HashAvailability.NONE,
    val packageName: String? = null,
    val modifiedBuild: ModifiedBuild = ModifiedBuild.NONE,
    /**
     * `true` if this row comes from reading the listing rather than from a result list.
     *
     * Where it is `false`, an empty [sizeBytes] means "not asked yet" and not "not published".
     */
    val listingRead: Boolean = false,
    /**
     * What the screen is doing about an unread row, right now.
     *
     * Only the two states that the **data cannot express** live here. Success is not among them: a
     * listing that has been read has versions, so [listingRead] turns `true` through Room's own flow
     * and the row simply starts carrying values. A `DONE` here would be a second copy of that fact,
     * free to disagree with it.
     */
    val read: ListingRead = ListingRead.IDLE,
)

/**
 * How far the reading of one unread listing has got.
 *
 * ### Why the table reads at all, when it used to read nothing
 *
 * The first draft of this screen made no requests, deliberately: filling it meant up to eight fetches to
 * third-party sites, and the rule against speculative prefetch is written across this project. What
 * changed is not the rule but where the gesture sits. Opening the comparison **is** the request —
 * nobody arrives here by scrolling, they arrive by pressing a button whose only purpose is to put
 * the stores side by side — and a comparison in which most cells say "not read yet" answers the
 * question it was opened to answer with a shrug.
 *
 * What has **not** changed: nothing is read on opening a listing, and nothing is read for a store
 * that has never been matched to this app. [CrossStoreRepository.lookUp] is still the only thing
 * that goes looking for stores, and it is still behind a button.
 */
enum class ListingRead {
    /** Nothing has been asked of this listing, or the answer arrived and the row now carries it. */
    IDLE,

    /** A request is in flight. */
    RUNNING,

    /**
     * It was asked and did not answer.
     *
     * A distinct state from [IDLE] because the cell has to stop promising: "not read yet" on a row
     * whose store has just refused is the one sentence on this screen that would be false.
     */
    FAILED,
}

/**
 * The same app as several stores publish it, side by side.
 *
 * ### Only the certain matches, and that is not a simplification
 *
 * The rows are the anchor plus `CrossStoreAvailability.availableOn`, i.e. matches at confidence
 * `≥ 0.85` or confirmed by a person. The "possible matches" stay off this table deliberately: a
 * comparison invites the reader to pick a row and install from it, and a row that might be a
 * different app is exactly what must never be offered that way. They keep their own section on the
 * listing, where the question asked is "is this the same app?" and not "which of these do I take?".
 *
 * [otherStoresUnexplored] carries `CrossStoreAvailability.unexploredStores` so the table can say how
 * many sources have not spoken. It is a count and not a search: asking them costs requests to
 * third-party sites, so it stays where it already is — behind the button on the listing, pressed by
 * a person.
 */
data class StoreComparison(
    val rows: List<StoreComparisonRow> = emptyList(),
    val otherStoresUnexplored: Int = 0,
    /**
     * The app's name, as the listing one arrived from writes it.
     *
     * It travels with the data rather than through the navigation route, because a title in a URL is
     * a second copy of a value the store can rewrite — and the two would disagree on exactly the
     * listings this table exists to compare. The anchor's is used and not a vote among the rows: nine
     * stores write nine names for one app ("Blockman Go" against "Blockman Go (MOD, Unlimited
     * Money)"), and picking one by majority would rename the page depending on who answered.
     */
    val title: String = "",
) {
    /**
     * `true` when there is a comparison to make.
     *
     * One row is the listing the reader is already on: a table of it alone would be the header they
     * just scrolled past, laid out as a grid.
     */
    val isComparable: Boolean get() = rows.size > 1
}
