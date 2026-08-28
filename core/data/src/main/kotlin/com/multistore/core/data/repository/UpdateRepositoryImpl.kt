package com.multistore.core.data.repository

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.mapper.selectVersion
import com.multistore.core.data.mapper.toDetail
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.data.system.PackageEvents
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionSettings
import com.multistore.store.api.SearchSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

@Singleton
internal class UpdateRepositoryImpl @Inject constructor(
    private val installedApps: InstalledAppsRepository,
    private val settings: SettingsRepository,
    private val details: AppDetailRepository,
    private val index: StoreIndexRepository,
    private val registry: StoreRegistry,
    private val catalogDao: CatalogDao,
    private val packageEvents: PackageEvents,
    private val device: DeviceProfile,
    @IoDispatcher private val io: CoroutineDispatcher,
) : UpdateRepository {

    /**
     * The update state, as a flow.
     *
     * Three sources of re-emission, and none is superfluous:
     *
     *  1. **the list of installed apps** — an installation or an uninstallation changes it;
     *  2. **the catalogue** — a check that has just finished has rewritten `app_versions`, and that is
     *     where the answer lives. Room invalidates a `@Query` based on the tables it names, and no
     *     query on `installed_apps` names `app_versions`: without this signal, a successful check
     *     would make nothing appear until something else was touched;
     *  3. **package changes** — half the answer comes from the `PackageManager`, which Room does not
     *     observe. Updating an app elsewhere has to make its row disappear from here.
     *
     * `mapLatest` and not `map`: an index sync invalidates the catalogue in bursts, and the
     * calculation in progress has to be abandoned rather than queued — the only result of interest is
     * the one on the latest state.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<InstalledAppUpdate>> =
        combine(
            installedApps.observe(),
            catalogDao.observeCatalogRevision(),
            packageEvents.changes.onStart { emit(null) },
            // The fourth source: switching preview channels on changes **which** version is the
            // answer, so it has to make the list reappear without waiting for something else to touch
            // the catalogue. It is also what keeps this answer the same as the listing's, which reads
            // the same switch from the same place.
            settings.versions,
        ) { apps, _, _, versionSettings -> apps to versionSettings }
            .mapLatest { (apps, versionSettings) -> apps.map { compute(it, versionSettings) } }
            .flowOn(io)

    override fun observeAvailable(): Flow<List<InstalledAppUpdate>> =
        observeAll().map { all -> all.filter { it.available != null } }

    override suspend fun all(): List<InstalledAppUpdate> = withContext(io) {
        val versionSettings = settings.versions.first()
        installedApps.all().map { compute(it, versionSettings) }
    }

    /**
     * Queries the update channels.
     *
     * ### What is queried, and what is not
     *
     * Only the listings the installed apps update from, and not even all of them: an app the user has
     * paused is not checked at all. `ignore_updates` is not only for silencing a notice — it is also
     * for **not making the request**, which is the half of the promise that matters to the store on
     * the other side.
     *
     * ### An indexed store updates in one go
     *
     * On F-Droid the listings are not re-read one by one: their freshness is a property of the index.
     * Thirty apps installed from F-Droid produce **one** sync, not thirty requests. On the stores read
     * page by page, by contrast, one request per app is unavoidable — and it is also why each one's
     * rate limiter remains the only thing setting the pace.
     *
     * ### The stores in parallel, the apps in a queue
     *
     * The rate limiter is per store: querying two stores together hurts neither, querying two apps of
     * the same store together would only make them wait in a queue. And a fault stops where it is
     * born: if apkmirror stops answering, its apps break off and F-Droid's carry on.
     */
    override suspend fun check(force: Boolean): UpdateCheckReport = withContext(io) {
        // Before asking anybody anything: remove the ghosts. An app uninstalled from the system
        // settings would stay in the table, and asking for its update would be a network request made
        // for an app that is not there.
        installedApps.reconcile()

        val targets = installedApps.all()
            .filter { !it.ignoreUpdates }
            .mapNotNull { app ->
                val storeId = app.updateChannelStoreId ?: return@mapNotNull null
                val ref = app.updateChannelRef ?: return@mapNotNull null
                Target(app, storeId, ref)
            }
        if (targets.isEmpty()) return@withContext UpdateCheckReport(checked = 0)

        val perStore = coroutineScope {
            targets.groupBy { it.storeId }
                .map { (storeId, forStore) -> async { checkStore(storeId, forStore, force) } }
                .awaitAll()
        }

        UpdateCheckReport(
            checked = perStore.sumOf { it.checked },
            failures = perStore.mapNotNull { outcome ->
                outcome.error?.let { outcome.storeId to it }
            }.toMap(),
        )
    }

    private suspend fun checkStore(
        storeId: StoreId,
        targets: List<Target>,
        force: Boolean,
    ): StoreCheck {
        val indexed = registry.adapter(storeId)?.capabilities?.searchSource == SearchSource.LOCAL_INDEX
        if (indexed) {
            // A single sync covers all this store's apps. `force` does not propagate: here it means
            // "ignore the listings' TTL", not "re-download eighteen megabytes of index", and confusing
            // the two would turn a tap on the "check now" button into a full resync.
            return when (val outcome = index.sync(storeId)) {
                is Outcome.Success -> StoreCheck(storeId, checked = targets.size, error = null)
                is Outcome.Failure -> StoreCheck(storeId, checked = 0, error = outcome.error)
            }
        }

        var checked = 0
        for (target in targets) {
            when (val outcome = details.refresh(storeId, target.ref, force = force)) {
                is Outcome.Success -> checked++
                is Outcome.Failure -> {
                    // A 404 concerns **that** app: the package has been withdrawn, and the same
                    // store's other listings are unaffected. Any other fault concerns the store, and
                    // insisting would be knocking harder on a door that has just said no — on a site
                    // answering 429 to whoever ignores its Crawl-delay, it is also the fastest way of
                    // getting the circuit opened.
                    if (outcome.error == AppError.NotFound) continue
                    return StoreCheck(storeId, checked, outcome.error)
                }
            }
        }
        return StoreCheck(storeId, checked, error = null)
    }

    /**
     * What is known about this app, now.
     *
     * The `packageName` the system is queried with comes from **`installed_apps`**, not from the
     * listing. The difference matters for four stores out of nine, which do not publish the
     * `packageName` at all: starting from the listing, an app taken from apkmody would come out not
     * installed, and every version of it a first installation instead of an update. We know that name
     * because the APK told us when we installed it.
     */
    private suspend fun compute(
        app: InstalledApp,
        versionSettings: VersionSettings,
    ): InstalledAppUpdate {
        val storeId = app.updateChannelStoreId
        val ref = app.updateChannelRef
        val listingId = app.updateChannelListingId
        if (storeId == null || ref == null || listingId == null) {
            return InstalledAppUpdate(app, channel = null, selection = null)
        }

        val rows = catalogDao.listing(storeId, ref.value)
            ?: return InstalledAppUpdate(app, channel = null, selection = null)
        val listing = rows.toDetail(catalogDao.app(rows.listing.appKey))
        val installed = installedApps.installedPackage(app.packageName)

        return InstalledAppUpdate(
            app = app,
            channel = UpdateChannel(
                storeId = storeId,
                ref = ref,
                listingId = listingId,
                title = listing.summary.title,
                iconUrl = listing.summary.iconUrl ?: app.iconUrl,
            ),
            selection = selectVersion(
                listing = listing,
                device = device,
                installed = installed,
                pinnedVersionCode = app.pinnedVersionCode,
                settings = versionSettings,
            ),
        )
    }

    private data class Target(val app: InstalledApp, val storeId: StoreId, val ref: StoreAppRef)

    private data class StoreCheck(val storeId: StoreId, val checked: Int, val error: AppError?)
}
