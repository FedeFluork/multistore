package com.multistore.core.data.repository

import com.multistore.core.common.result.Outcome
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.model.AppVersion
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.UsesPermission
import com.multistore.core.model.VersionRef
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
    /**
     * Where the next update for this app will come from, when MultiStore installed it.
     *
     * ### It is not the same thing as where it came from
     *
     * `installed_apps` has carried `update_channel_listing_id` apart from `source_ref` since M3, and
     * the two coincide until somebody changes channel — i.e. they differ at exactly the moment the
     * difference matters. Until 0.8.0 **no screen changed it**: the column existed, the model kept
     * it distinct from provenance, and the gesture that gives it meaning did not exist.
     *
     * `null` when the app is not installed, when it was installed outside MultiStore, or when the
     * channel points at a listing a sync has since deleted. The last is deliberate and no foreign
     * key prevents it: a package withdrawn from a store is no reason to forget the user has it.
     */
    val updateChannel: InstalledUpdateChannel? = null,
)

/**
 * The listing an installed app updates from.
 *
 * The name says *installed*, because `UpdateRepository` already has an `UpdateChannel` and the two
 * answer different questions: that one describes an update **row** — listing id, title, icon, for
 * whoever draws "update available" — while this one is the pointer plus the signature to compare
 * against, which is what deciding to *change* channel needs.
 *
 * A type of its own rather than three nullable fields on [AppDetail], because they are only ever
 * meaningful together: a store with no ref points at nothing, and a screen holding one without the
 * other would have to invent what to do about it.
 */
data class InstalledUpdateChannel(
    val storeId: StoreId,
    val ref: StoreAppRef,
    /**
     * The signer of the app **as it is installed**, from the `PackageManager`.
     *
     * It rides here rather than being looked up again by whoever offers the channel switch, because
     * this is the value the warning compares against: two stores redistributing one app almost never
     * sign it with the same key, and an update across that boundary is refused by the operating
     * system — after the download, with a message about the archive.
     */
    val installedSignerSha256: Sha256? = null,
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

    /**
     * Records what a build asks the operating system for, read from the archive we just verified.
     *
     * ### Why it is written back at all, instead of being shown and forgotten
     *
     * Eight stores of nine publish nothing about permissions, so the file is the only source — and
     * the file exists for the length of one installation. Writing it into `app_versions` turns a
     * fact known once into a fact the listing carries: the next time somebody opens that version, or
     * compares it against another store's, the answer is there without a download.
     *
     * ### It is not part of the pipeline, and it must not become part of it
     *
     * Nothing here refuses an installation. The list is information, and a caller ignoring its
     * result loses nothing but a cached answer — which is exactly why it is a separate call rather
     * than a field verification returns. F-Droid never needs it: the index publishes the list, so
     * the catalogue already has it before anything is downloaded.
     *
     * A version that is not (or no longer) in the catalogue writes nothing. That is ordinary — the
     * self-update installs an APK that belongs to no listing at all — and not a failure.
     */
    suspend fun recordPermissions(
        storeId: StoreId,
        ref: StoreAppRef,
        versionRef: VersionRef,
        permissions: List<UsesPermission>,
    )
}
