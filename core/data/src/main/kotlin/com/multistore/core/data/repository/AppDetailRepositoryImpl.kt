package com.multistore.core.data.repository

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.mapper.isStale
import com.multistore.core.data.mapper.selectVersion
import com.multistore.core.data.mapper.versionOffers
import com.multistore.core.data.mapper.toAppError
import com.multistore.core.data.mapper.toDetail
import com.multistore.core.data.mapper.toRows
import com.multistore.core.data.mapper.toVersionRows
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.data.system.PackageEvents
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.database.dao.ListingWithDetails
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.VersionSettings
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

@Singleton
internal class AppDetailRepositoryImpl @Inject constructor(
    private val registry: StoreRegistry,
    private val catalogDao: CatalogDao,
    private val installedApps: InstalledAppsRepository,
    private val settings: SettingsRepository,
    private val packageEvents: PackageEvents,
    private val health: StoreHealthRepository,
    private val device: DeviceProfile,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AppDetailRepository {

    /**
     * The listing as a flow, with **two** sources of re-emission.
     *
     * Room covers the catalogue. But half of what this listing says — "installed, version X", and
     * with it the version-choice outcome — comes from the `PackageManager`, which Room does not
     * observe: without the second flow, uninstalling the app left the listing declaring it still
     * installed until something else touched the database.
     *
     * `onStart { emit(null) }` is not cosmetic: `combine` does not emit until **both** flows have
     * produced a value, and package changes are rare events — without an initial value the listing
     * would stay blank until the device's next installation.
     */
    override fun observe(storeId: StoreId, ref: StoreAppRef): Flow<AppDetail?> =
        combine(
            catalogDao.observeListing(storeId, ref.value),
            packageEvents.changes.onStart { emit(null) },
            settings.versions,
        ) { rows, _, versionSettings -> rows to versionSettings }
            .map { (rows, versionSettings) -> rows?.let { compose(it, versionSettings) } }

    override suspend fun detail(storeId: StoreId, ref: StoreAppRef): AppDetail? = withContext(io) {
        catalogDao.listing(storeId, ref.value)?.let { compose(it, settings.versions.first()) }
    }

    override suspend fun refresh(
        storeId: StoreId,
        ref: StoreAppRef,
        force: Boolean,
    ): Outcome<Unit> = withContext(io) {
        val adapter = registry.adapter(storeId)
            ?: return@withContext Outcome.Failure(AppError.NotFound)

        if (adapter.capabilities.searchSource == SearchSource.LOCAL_INDEX) {
            // On an indexed store, a listing's freshness is not a property of the listing: it is a
            // property of the index, and it is renewed by syncing it. Asking for the single page here
            // would mean a network request that does not exist — and indeed `getAppDetails` answers
            // `Unsupported`. Whoever wants fresher data calls `StoreIndexRepository.sync`.
            return@withContext Outcome.Success(Unit)
        }

        val cached = catalogDao.listing(storeId, ref.value)
        if (!force && cached != null && !cached.listing.isStale(clock.now())) {
            return@withContext Outcome.Success(Unit)
        }
        if (!health.canAttempt(storeId)) {
            return@withContext Outcome.Failure(AppError.Blocked("circuit open"))
        }

        when (val result = adapter.getAppDetails(ref)) {
            is StoreResult.Success -> {
                health.recordSuccess(storeId)
                save(result.value, adapter.capabilities.listingTtl)
                Outcome.Success(Unit)
            }

            is StoreResult.Failure -> {
                health.recordFailure(storeId, result.error)
                Outcome.Failure(result.error.toAppError())
            }

            // A capability saying "I do not know how to answer" is not a store fault: what is in
            // cache stays valid and the screen must not show an error.
            StoreResult.Unsupported -> Outcome.Success(Unit)
        }
    }

    /**
     * The versions the store publishes **beyond** the listing's.
     *
     * Three early exits, and none of them is an error to show:
     *
     *  - **capability off** — an1 publishes a listing, a file, and nothing else: there is no page to
     *    ask for;
     *  - **local-index store** — the index already carries them all, and `getVersions` answers
     *    `Unsupported`. Asking anyway would not be wrong, it would be pointless;
     *  - **circuit open** — the store has just stopped answering, and this is a request the user can
     *    make again in a minute.
     *
     * A failure, by contrast, **is declared**: the section opened because somebody opened it, and
     * returning `Success` for a page that did not answer would leave the listing's only version on
     * screen as though it were the whole truth.
     */
    override suspend fun loadVersionHistory(
        storeId: StoreId,
        ref: StoreAppRef,
    ): Outcome<Unit> = withContext(io) {
        val adapter = registry.adapter(storeId)
            ?: return@withContext Outcome.Failure(AppError.NotFound)

        if (!adapter.capabilities.versionHistory) return@withContext Outcome.Success(Unit)
        if (adapter.capabilities.searchSource == SearchSource.LOCAL_INDEX) {
            return@withContext Outcome.Success(Unit)
        }
        if (!health.canAttempt(storeId)) {
            return@withContext Outcome.Failure(AppError.Blocked("circuit open"))
        }

        when (val result = adapter.getVersions(ref)) {
            is StoreResult.Success -> {
                health.recordSuccess(storeId)
                catalogDao.mergeVersions(storeId, ref.value, result.value.toVersionRows(clock.now()))
                Outcome.Success(Unit)
            }

            is StoreResult.Failure -> {
                health.recordFailure(storeId, result.error)
                Outcome.Failure(result.error.toAppError())
            }

            // A capability declared `true` by an adapter that then answers `Unsupported` is a
            // contradiction the contract test catches. Here it is not a fault: what is there stays,
            // and the section shows the listing's versions.
            StoreResult.Unsupported -> Outcome.Success(Unit)
        }
    }

    private suspend fun save(detail: StoreListingDetail, ttl: kotlin.time.Duration) {
        catalogDao.saveListings(listOf(detail.toRows(clock.now(), ttl)))
    }

    /**
     * Puts together the listing, the device's state and the version-choice outcome.
     *
     * The installed signer and version code come from the `PackageManager`, not from
     * `installed_apps`: that table says what was there when we wrote it, and between then and now the
     * user may have updated the app elsewhere. For a security decision the source has to be the
     * authoritative one.
     */
    private suspend fun compose(
        rows: ListingWithDetails,
        versionSettings: VersionSettings,
    ): AppDetail {
        val app = catalogDao.app(rows.listing.appKey)
        val listing = rows.toDetail(app)
        // What we know about this app, which is not what the listing says about it.
        //
        // Two reads, and the second is not a convenient fallback: **four stores out of nine do not
        // publish the `packageName`**, so from their listing there is no telling which package to
        // query, and without that name the listing would say "Install" to someone who already has the
        // app — forever, because there is nothing that could make it change its mind. But we do know
        // the name: the APK told us when we installed it, and the `installed_apps` row is found
        // starting from the listing.
        //
        // The version pin comes from here too, and it is a user's choice and not a fact about the
        // device. An app installed **outside** MultiStore has no row, and therefore no pin to
        // respect: that is correct.
        val tracked = listing.summary.packageName?.let { installedApps.get(it) }
            ?: installedApps.forListing(rows.listing.storeId, StoreAppRef(rows.listing.storeAppRef))
        val packageName = listing.summary.packageName ?: tracked?.packageName
        val installed = packageName?.let { installedApps.installedPackage(it) }
        val selection = selectVersion(
            listing = listing,
            device = device,
            installed = installed,
            pinnedVersionCode = tracked?.pinnedVersionCode,
            settings = versionSettings,
        )
        return AppDetail(
            listing = listing,
            installed = installed,
            selection = selection,
            stale = rows.listing.isStale(clock.now()),
            versions = versionOffers(listing = listing, device = device, installed = installed),
            // It is built by the adapter, the only one that knows what shape its `ref` has.
            listingUrl = registry.adapter(rows.listing.storeId)
                ?.listingUrl(StoreAppRef(rows.listing.storeAppRef)),
        )
    }
}
