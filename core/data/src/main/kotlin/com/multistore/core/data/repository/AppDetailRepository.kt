package com.multistore.core.data.repository

import com.multistore.core.common.result.Outcome
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.model.AppVersion
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import kotlinx.coroutines.flow.Flow

/**
 * A listing ready to show: what the store publishes, what is on the device, what can be done.
 *
 * The three pieces sit together because the user's question is a single one — "can I install it, and
 * what happens if I do" — and answering requires all three. Keeping them separate would force every
 * screen to recompose them, and to get it wrong in different ways.
 */
data class AppDetail(
    val listing: StoreListingDetail,
    /** What the `PackageManager` says **now**, not what our database says. */
    val installed: InstalledPackage?,
    /** The version-choice rule's outcome: what to offer, or why it cannot be. */
    val selection: VersionSelection.Outcome,
    /** `true` if the cached row is past its TTL: it is shown, marked, and refreshed. */
    val stale: Boolean,
    /**
     * Every published version, newest first, with **what happens when it is pressed**.
     *
     * It is not a duplicate of `listing.versions`: it is that list plus a verdict depending on the
     * device and on what is installed now. It lives here and not in the screen for the same reason as
     * [selection], which depends on the same `DeviceProfile`: the device profile is a fact
     * `:core:data` knows and that a ViewModel must not have to ask for — and with two different
     * readers they would become two answers to the same question.
     */
    val versions: List<VersionOffer> = emptyList(),
    /**
     * This listing's page on the store's site, to open in the browser.
     *
     * It is built by the adapter (`StoreAdapter.listingUrl`) and not by this class: the path's shape
     * is the only thing `StoreAppRef` hides, and the core never builds a URL. `null` where there is no
     * page — none of the nine today, but the contract allows it.
     */
    val listingUrl: String? = null,
)

/** A version from the history, and what can be done with it on **this** device. */
data class VersionOffer(
    val version: AppVersion,
    val installability: VersionSelection.Installability,
)

/**
 * An app's detail listing on a store.
 *
 * It is the **second** of the two points where the `searchSource` capability forks the code (the
 * first is `SearchRepository`):
 *
 *  - **`LOCAL_INDEX`** — the listing is already in Room, written by the sync. No request, and
 *    therefore no circuit breaker: [refresh] on those stores does not ask for the single page but
 *    does nothing, because a listing's freshness depends on the whole index.
 *  - **`REMOTE`** — what is in cache is shown immediately, marked if expired, and refreshed in the
 *    background. It is stale-while-revalidate.
 */
interface AppDetailRepository {

    /**
     * The listing as a flow: Room re-emits by itself when the refresh updates it.
     *
     * It emits `null` until there is nothing to show — no cache and no result — so that whoever draws
     * can tell "I am loading" from "it does not exist".
     */
    fun observe(storeId: StoreId, ref: StoreAppRef): Flow<AppDetail?>

    suspend fun detail(storeId: StoreId, ref: StoreAppRef): AppDetail?

    /**
     * Asks the store for **all** the versions it publishes, and adds them to those in the catalogue.
     *
     * ### Why it is a separate call, and does not arrive with the listing
     *
     * On three of the nine stores the history lives on a page of its own — apkcombo `/old-versions`,
     * apkmody `/history`, modyolo — so having it costs **one more request to a third-party site**.
     * Making it on opening every listing would be the speculative prefetch this project forbids: it
     * is made when the user opens the section, i.e. when they have asked for it.
     *
     * On the other six it costs nothing new and is not useless: four answer with the same versions as
     * the listing (and then [CatalogDao.mergeVersions] changes nothing), and F-Droid answers
     * `Unsupported` because its index already carries them all. The
     * [StoreCapabilities.versionHistory] capability avoids the request where there is nothing to ask
     * — and it is the first reader that capability has ever had: eight adapters out of nine declared
     * it `true` and nobody called `getVersions`.
     *
     * It does not return the versions: it **writes** them, and Room re-emits from [observe].
     * Returning them would mean a second copy in the screen's hands, which would have to merge it
     * with the flow's — and getting that wrong would give a history that vanishes at the listing's
     * first update.
     */
    suspend fun loadVersionHistory(storeId: StoreId, ref: StoreAppRef): Outcome<Unit>

    /**
     * Refreshes the listing from the source, if the source can be queried one listing at a time.
     *
     * @param force ignore the TTL. It serves the pull-to-refresh gesture, which has to do something
     * even when the data is formally fresh.
     */
    suspend fun refresh(storeId: StoreId, ref: StoreAppRef, force: Boolean = false): Outcome<Unit>
}
