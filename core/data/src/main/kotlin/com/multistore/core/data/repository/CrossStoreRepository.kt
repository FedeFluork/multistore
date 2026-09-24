package com.multistore.core.data.repository

import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import kotlinx.coroutines.flow.Flow

/**
 * A store that has — or might have — the same app.
 *
 * [listingId] is present only when the listing is already a row of `store_listings`. That is not a
 * detail: without an id the user's correction cannot be recorded in `identity_overrides`, so
 * "confirm" and "reject" are offered only where there is one.
 */
data class StoreAvailability(
    val listing: AggregatedListing,
    val listingId: Long? = null,
) {
    val storeId: StoreId get() = listing.storeId
    val ref: StoreAppRef get() = listing.ref
}

/** How far the search on the other stores has got. */
enum class CrossStoreLookup { IDLE, RUNNING, DONE }

/**
 * Where else this app is found, and where it **might** be.
 *
 * The separation between the two lists is the safety rule made a fact of the type: [availableOn] are
 * matches at confidence `≥ 0.85` or confirmed by a person, [possibleMatches] everything else. There
 * is no field joining them, because a screen wanting to show them together would have to write it by
 * hand — and at that point it is a decision, not an oversight.
 */
data class CrossStoreAvailability(
    val availableOn: List<StoreAvailability> = emptyList(),
    val possibleMatches: List<StoreAvailability> = emptyList(),
    val lookup: CrossStoreLookup = CrossStoreLookup.IDLE,
    /** How many enabled stores are not yet represented: below one, searching makes no sense. */
    val unexploredStores: Int = 0,
) {
    /**
     * `true` if it is worth offering the "search the other stores" button.
     *
     * It also disappears **after a completed search**, not only while one is running. Some stores do
     * not have the app and will not have it at the next tap: without this condition the button would
     * stay there forever and every press would remake the same requests to the same sites for the
     * same answer. Once asked, it has been asked.
     */
    val canLookUp: Boolean get() = lookup == CrossStoreLookup.IDLE && unexploredStores > 0

    /**
     * The highest version **another** store already in the catalogue publishes for this app.
     *
     * ### What it replaces, and why it is not the Play Store
     *
     * The real question is "is this store behind the original?", and the natural answer would be the
     * version on Google Play. **It is not published**: measured on 27/08/2026 across five packages
     * (Spotify, Telegram, Duolingo, Firefox, Netflix), the word "Version" does not appear on the Play
     * page with either a mobile or a desktop UA, and the numbers resembling it sit inside the
     * **reviews** — "reviewed on 154.0" — not in the listing. The only remaining route would be the
     * internal `batchexecute` RPC, which already answers 400 to a request in the historical format.
     *
     * This comparison is the other half of the same question, and it has a property the other would
     * not: it is **data the app already holds**, it costs no request, and it cannot break silently
     * the day someone changes some markup.
     *
     * ### The three conditions, and what each prevents
     *
     *  - **only** [availableOn] is looked at: a match below the 0.85 threshold sits in
     *    [possibleMatches] because it might be another app, and announcing "elsewhere there is 12.0"
     *    of another app is worse than saying nothing;
     *  - **two `versionCode`s** are needed, ours and theirs. Four stores out of nine do not publish
     *    it, and comparing two `versionName`s as strings would say `9.9` comes after `10.1`. It is
     *    the same honesty as `UpToDate.comparable`: the absence of the datum does not disguise itself
     *    as an answer;
     *  - a **`versionName` to show** is needed: an internal number like `2030099002` next to a
     *    version name is not a comparison, it is a riddle.
     */
    fun newerThan(installedVersionCode: Long?): NewerElsewhere? {
        val ours = installedVersionCode ?: return null
        return availableOn
            .mapNotNull { other ->
                val summary = other.listing.summary
                val code = summary.latestVersionCode ?: return@mapNotNull null
                val name = summary.latestVersionName ?: return@mapNotNull null
                NewerElsewhere(storeId = summary.storeId, versionName = name, versionCode = code)
            }
            .filter { it.versionCode > ours }
            .maxByOrNull { it.versionCode }
    }
}

/** A higher version found on another store, with the store publishing it. */
data class NewerElsewhere(
    val storeId: StoreId,
    val versionName: String,
    val versionCode: Long,
)

/**
 * Cross-store identity from **one** open listing's point of view.
 *
 * Three sources, in increasing order of cost and all three necessary:
 *
 *  1. **Room** — the listings that already share an `app_key`. Zero cost, but it only knows about
 *     what has been opened or synced before.
 *  2. **The last search's memory** — if we arrived here from a search, the aggregation has already
 *     seen the other stores. Zero cost, and it covers the main path.
 *  3. **[lookUp]** — the explicit question to the other stores. It costs network requests, so it is
 *     made by **the user** pressing a button: speculative prefetch is forbidden, and four searches
 *     on third-party sites on every listing opening would be exactly that.
 */
interface CrossStoreRepository {

    fun observe(storeId: StoreId, ref: StoreAppRef): Flow<CrossStoreAvailability>

    /**
     * The same listings as [observe]'s `availableOn`, plus the anchor, side by side.
     *
     * ### Why it lives here and not in a repository of its own
     *
     * Everything it needs is what this interface already answers — which stores have this app, at
     * what confidence, and which of them a person has confirmed. A second repository would have to
     * re-derive the 0.85 rule and the `identity_overrides` exceptions, and the day the two derivations
     * disagreed the table would offer a row the listing refuses to merge, or the reverse. It is the
     * "two answers to the same question" this project forbids elsewhere, applied to identity.
     *
     * It costs **no request**: version, date, package name and rework flag are columns of
     * `store_listings`, the size is a sub-query over `app_versions`, and whether a store publishes a
     * hash is the adapter's own declaration. Asking the stores that have not spoken stays where it
     * already is, behind [lookUp] and a person's tap.
     */
    fun compare(storeId: StoreId, ref: StoreAppRef): Flow<StoreComparison>

    /**
     * Reads the listings in a comparison that nobody has opened, so the table is a comparison.
     *
     * ### What it does and does not go looking for
     *
     * Only the stores already matched to this app — [CrossStoreAvailability.availableOn] — and among
     * those only the rows born in a **result list**, which carry no versions and therefore no size,
     * no version name and no package. Finding new stores is still [lookUp]'s job and is still behind
     * a button of its own: this refreshes what is already known to be there, it does not go
     * knocking.
     *
     * ### It is a request per unread row, and the caller is a deliberate gesture
     *
     * Up to eight fetches to third-party sites. That is affordable here and would not be on the
     * listing, and the difference is who asked: nobody reaches this screen except by pressing
     * "Compare". Rows already read cost nothing — the TTL decides, exactly as it does everywhere
     * else — and a store that fails says so on its own card rather than failing the screen.
     *
     * Calling it twice for the same app while the first call is in flight does nothing.
     */
    suspend fun readListings(storeId: StoreId, ref: StoreAppRef)

    /** Asks the stores that have not yet spoken. On the user's request, never by itself. */
    suspend fun lookUp(storeId: StoreId, ref: StoreAppRef)

    /**
     * "Yes, it is the same app." The listing joins the group, and stays there.
     *
     * From this moment the match is no longer a heuristic: `match_method` becomes `USER_CONFIRMED`
     * and the confidence `1.0`, because no similarity measure beats a person who has looked at the
     * two listings.
     */
    suspend fun confirm(anchor: StoreId, anchorRef: StoreAppRef, candidateListingId: Long)

    /** "No, they are two different apps." It will not be proposed for this app again. */
    suspend fun reject(anchor: StoreId, anchorRef: StoreAppRef, candidateListingId: Long)

    companion object {
        /** How many "possible matches" are worth proposing. Beyond that it is a list of noise. */
        const val MAX_CANDIDATES: Int = 5
    }
}
