package com.multistore.core.testing

import androidx.paging.PagingData
import com.multistore.core.common.net.StoreHealth
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.AppDetailRepository
import com.multistore.core.data.repository.CrossStoreAvailability
import com.multistore.core.data.repository.CrossStoreRepository
import com.multistore.core.data.repository.DownloadRepository
import com.multistore.core.data.repository.DownloadStatus
import com.multistore.core.data.repository.HealthEvent
import com.multistore.core.data.repository.IndexState
import com.multistore.core.data.repository.IndexSyncProgress
import com.multistore.core.data.repository.IndexSyncReport
import com.multistore.core.data.repository.InstallPlan
import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.InstallStep
import com.multistore.core.data.repository.InstalledAppUpdate
import com.multistore.core.data.repository.InstalledAppsRepository
import com.multistore.core.data.repository.SearchPage
import com.multistore.core.data.repository.SearchProgress
import com.multistore.core.data.repository.SearchRepository
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.data.repository.StoreHealthRepository
import com.multistore.core.data.repository.StoreIndexRepository
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.data.repository.UpdateCheckReport
import com.multistore.core.data.repository.UpdateRepository
import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.ContentKind
import com.multistore.core.model.DownloadHistoryLimit
import com.multistore.core.model.DownloadState
import com.multistore.core.model.InstallSettings
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.NetworkSettings
import com.multistore.core.data.repository.RemoteConfigRepository
import com.multistore.core.model.RemoteConfigSettings
import com.multistore.core.model.SearchSort
import com.multistore.core.remoteconfig.FetchAttempt
import com.multistore.core.remoteconfig.RemoteConfigStatus
import com.multistore.core.model.DiagnosticsSettings
import com.multistore.core.model.NotificationSettings
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SecuritySettings
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.CatalogRetention
import com.multistore.core.model.StorageSettings
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.UpdateInterval
import com.multistore.core.model.UpdateSettings
import com.multistore.core.model.VersionSettings
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.StoreError
import java.io.File
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The repositories' test doubles, in one place.
 *
 * They live in `:core:testing` for the same reason [ScreenshotTest] does: without a common place,
 * seven feature modules would end up with seven slightly different versions of the same fake, and the
 * seventh would diverge from the contract without anyone noticing — because a fake that no longer
 * matches the interface **does not compile**, but a fake that answers differently from the other six
 * does.
 *
 * They are deliberately **recorders** and not simulators: they count the calls and return what was
 * set. Reproducing a repository's real behaviour in a fake means testing the fake.
 */

/** Searches, browses, and remembers what it was asked. */
class FakeSearchRepository : SearchRepository {

    /** The searches that arrived, in order: `(query, page)`. */
    val searches = mutableListOf<Pair<String, Int>>()

    /** Each arriving search's filters, in the same order as [searches]. */
    val searchFilters = mutableListOf<SearchFilters>()

    /** The stores asked for on each search. Empty = "the switched-on ones", as the repository means. */
    val searchStores = mutableListOf<Set<StoreId>>()

    /** The browse calls that arrived, in order: `(categoryId, page)`. `null` = the whole catalogue. */
    val browsed = mutableListOf<Pair<String?, Int>>()

    var page: SearchPage = SearchPage(apps = emptyList(), page = 0, hasMore = false)
    var recentlyUpdated: List<StoreListingSummary> = emptyList()
    var catalogue: List<StoreListingSummary> = emptyList()

    /** Called before answering: the point at which a test can change the answer halfway. */
    var onSearch: (String, Int) -> SearchPage = { _, _ -> page }

    /** Like [onSearch], for browsing: it receives `(categoryId, page)`. */
    var onBrowse: (String?, Int) -> List<StoreListingSummary> = { _, _ -> catalogue }

    /**
     * The intermediate states to emit **before** the final one.
     *
     * It serves to prove what the staged search has of its own: the list filling up while the stores
     * answer. A fake emitting only the final outcome would make a streaming ViewModel
     * indistinguishable from one that waits for everything.
     */
    var partials: List<SearchProgress> = emptyList()

    override fun searchStreaming(
        query: String,
        storeIds: Set<StoreId>,
        page: Int,
        filters: SearchFilters,
    ): Flow<SearchProgress> = flow {
        searches += query to page
        searchFilters += filters
        searchStores += storeIds
        partials.forEach {
            emit(it)
            // One virtual millisecond between emissions, and it is not cosmetic: a ViewModel's state is
            // a `StateFlow`, which **conflates**. Emitted one after another without ever suspending, the
            // intermediate phases would never reach a collector, and a streaming test would see only
            // the final outcome — i.e. would not tell streaming from waiting. In reality that time is
            // the network latency separating two stores.
            delay(EMISSION_GAP_MILLIS)
        }
        val finalPage = onSearch(query, page)
        emit(SearchProgress(finalPage, answered = emptySet(), pending = emptySet()))
    }

    private companion object {
        const val EMISSION_GAP_MILLIS = 1L
    }

    override suspend fun search(
        query: String,
        storeIds: Set<StoreId>,
        page: Int,
        filters: SearchFilters,
    ): SearchPage {
        searches += query to page
        searchFilters += filters
        searchStores += storeIds
        return onSearch(query, page)
    }

    override suspend fun recentlyUpdated(storeId: StoreId, page: Int): List<StoreListingSummary> =
        recentlyUpdated

    override suspend fun browse(
        storeId: StoreId,
        categoryId: String?,
        page: Int,
    ): List<StoreListingSummary> {
        browsed += categoryId to page
        return onBrowse(categoryId, page)
    }

    /**
     * The paged catalogue, served by [onBrowse] as a single page.
     *
     * `PagingData.from` and not a fake `Pager`: there is no database to invalidate here, and what a
     * ViewModel test has to be able to prove is **which rows arrive for which category** — not that
     * Paging can paginate, which is a fact about Paging.
     */
    override fun browsePaged(
        storeId: StoreId,
        categoryId: String?,
    ): Flow<PagingData<StoreListingSummary>> {
        browsed += categoryId to 0
        return flowOf(completePage(onBrowse(categoryId, 0)))
    }
}

/**
 * Cross-store matching, with the outcome decided by the test and the actions **recorded**.
 *
 * The three call lists count as much as the state: the rule is that below `0.85` nothing is merged on
 * our own, so what a test has to be able to prove is not only "the section shows two stores" but
 * "pressing Confirm recorded a confirmation, and nothing else did".
 */
class FakeCrossStoreRepository : CrossStoreRepository {

    private val state = MutableStateFlow(CrossStoreAvailability())

    val lookUps = mutableListOf<Pair<StoreId, StoreAppRef>>()
    val confirmed = mutableListOf<Long>()
    val rejected = mutableListOf<Long>()

    fun emit(availability: CrossStoreAvailability) {
        state.value = availability
    }

    override fun observe(storeId: StoreId, ref: StoreAppRef): Flow<CrossStoreAvailability> = state

    override suspend fun lookUp(storeId: StoreId, ref: StoreAppRef) {
        lookUps += storeId to ref
    }

    override suspend fun confirm(anchor: StoreId, anchorRef: StoreAppRef, candidateListingId: Long) {
        confirmed += candidateListingId
    }

    override suspend fun reject(anchor: StoreId, anchorRef: StoreAppRef, candidateListingId: Long) {
        rejected += candidateListingId
    }
}

/** A store's local index, with the state and the taxonomy settable by the test. */
class FakeStoreIndexRepository(
    state: IndexState? = null,
    taxonomy: StoreTaxonomy = StoreTaxonomy(),
) : StoreIndexRepository {

    val states = MutableStateFlow(state)
    val taxonomies = MutableStateFlow(taxonomy)

    /** How many syncs were actually requested. */
    var syncs = 0
        private set
    var lastForce: Boolean? = null
        private set

    var result: Outcome<IndexSyncReport> = Outcome.Failure(AppError.NotFound)

    /** Run inside `sync`, before answering: it is there to simulate progress. */
    var onSync: (IndexSyncProgress) -> Unit = {}

    override fun observeState(storeId: StoreId): Flow<IndexState?> = states

    override suspend fun state(storeId: StoreId): IndexState? = states.value

    override fun observeTaxonomy(storeId: StoreId): Flow<StoreTaxonomy> = taxonomies

    override suspend fun taxonomy(storeId: StoreId): StoreTaxonomy = taxonomies.value

    override suspend fun sync(
        storeId: StoreId,
        force: Boolean,
        onProgress: (IndexSyncProgress) -> Unit,
    ): Outcome<IndexSyncReport> {
        syncs++
        lastForce = force
        onSync(IndexSyncProgress(mode = com.multistore.store.api.IndexSyncMode.FULL, processed = 0, expected = null))
        return result
    }
}

/**
 * The stores' health, in memory.
 *
 * It serves where a screen has to **list** the stores — the search's filter panel — and not where it
 * measures their state: the real breaker has its own state machine and its own tests. Here the stores
 * are all healthy, which is the condition in which everything else is proven.
 */
class FakeStoreHealthRepository(entries: List<StoreEntry> = emptyList()) : StoreHealthRepository {

    val stores = MutableStateFlow(entries)
    val events = mutableListOf<HealthEvent>()
    val successes = mutableListOf<StoreId>()

    override suspend fun registerKnownStores() = Unit

    override fun observeAll(): Flow<List<StoreHealth>> = MutableStateFlow(emptyList())

    override fun observeStores(): Flow<List<StoreEntry>> = stores

    override suspend fun health(storeId: StoreId): StoreHealth = StoreHealth(storeId)

    override suspend fun canAttempt(storeId: StoreId): Boolean = true

    override suspend fun recordSuccess(storeId: StoreId) {
        successes += storeId
    }

    override suspend fun recordFailure(storeId: StoreId, error: StoreError) = Unit

    override suspend fun recordEvent(
        storeId: StoreId,
        kind: String,
        selector: String?,
        tier: Int?,
        detail: String?,
        durationMillis: Long?,
    ) {
        events += HealthEvent(storeId, kind, selector, tier, detail, durationMillis, Instant.DISTANT_PAST)
    }

    override suspend fun recentEvents(limit: Int): List<HealthEvent> = events.takeLast(limit).reversed()

    override suspend fun pruneOldEvents() = Unit

    override suspend fun setEnabled(storeId: StoreId, enabled: Boolean) {
        stores.value = stores.value.map { if (it.storeId == storeId) it.copy(enabled = enabled) else it }
    }
}

/** The settings, in memory. */
class FakeSettingsRepository(
    meteredNetworkAllowed: Boolean = false,
    blockUserAssistedChallenge: Boolean = false,
) : SettingsRepository {

    override val appearance = MutableStateFlow(AppearanceSettings(ThemeMode.SYSTEM))
    override val updates = MutableStateFlow(UpdateSettings())
    override val versions = MutableStateFlow(VersionSettings())
    override val installation = MutableStateFlow(InstallSettings())
    override val security = MutableStateFlow(SecuritySettings())
    override val remoteConfig = MutableStateFlow(RemoteConfigSettings())
    override val search = MutableStateFlow(SearchSettings())
    override val notifications = MutableStateFlow(NotificationSettings())
    override val diagnostics = MutableStateFlow(DiagnosticsSettings())
    override val network = MutableStateFlow(
        NetworkSettings(
            meteredNetworkAllowed = meteredNetworkAllowed,
            blockUserAssistedChallenge = blockUserAssistedChallenge,
        ),
    )
    override val storage = MutableStateFlow(StorageSettings())

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        appearance.value = appearance.value.copy(themeMode = themeMode)
    }

    override suspend fun setAllowPreviewChannels(allow: Boolean) {
        versions.value = versions.value.copy(allowPreviewChannels = allow)
    }

    override suspend fun setDynamicColor(enabled: Boolean) = Unit
    override suspend fun setLanguageTag(tag: String) = Unit
    override suspend fun setUpdateInterval(interval: UpdateInterval) {
        updates.value = updates.value.copy(interval = interval)
    }

    override suspend fun setUpdateOnlyWhenCharging(only: Boolean) {
        updates.value = updates.value.copy(onlyWhenCharging = only)
    }

    override suspend fun setAutoDownloadUpdates(auto: Boolean) {
        updates.value = updates.value.copy(autoDownload = auto)
    }

    override suspend fun setAutoInstallUpdates(auto: Boolean) {
        updates.value = updates.value.copy(autoInstall = auto)
    }

    override suspend fun setMuteUpdateNotifications(mute: Boolean) {
        updates.value = updates.value.copy(muteNotifications = mute)
    }

    override suspend fun setInstallerPreference(preference: InstallerPreference) {
        installation.value = InstallSettings(preference)
    }

    override suspend fun setAllowUnverifiedHash(allow: Boolean) = Unit
    override suspend fun setAllowSignerMismatch(allow: Boolean) = Unit

    override suspend fun setBlockRemoteParsers(block: Boolean) {
        remoteConfig.value = remoteConfig.value.copy(blockRemoteParsers = block)
    }

    override suspend fun setBlockRemoteIndex(block: Boolean) {
        remoteConfig.value = remoteConfig.value.copy(blockRemoteIndex = block)
    }

    override suspend fun setBlockSelfUpdateCheck(block: Boolean) {
        remoteConfig.value = remoteConfig.value.copy(blockSelfUpdateCheck = block)
    }

    override suspend fun setShowNsfwContent(show: Boolean) {
        search.value = search.value.copy(showNsfwContent = show)
    }

    override suspend fun setSearchTimeout(timeout: Duration) {
        search.value = search.value.copy(storeTimeout = timeout)
    }

    override suspend fun setDefaultSort(sort: SearchSort) {
        search.value = search.value.copy(defaultSort = sort)
    }

    override suspend fun setKeepApkAfterInstall(keep: Boolean) {
        storage.value = storage.value.copy(keepApkAfterInstall = keep)
    }

    override suspend fun setImageCacheMaxMb(megabytes: Int) {
        storage.value = storage.value.copy(imageCacheMaxBytes = StorageSettings.megabytes(megabytes))
    }

    override suspend fun setCatalogRetention(retention: CatalogRetention) {
        storage.value = storage.value.copy(catalogRetention = retention)
    }

    override suspend fun setDownloadHistoryLimit(limit: DownloadHistoryLimit) {
        storage.value = storage.value.copy(downloadHistoryLimit = limit)
    }

    override suspend fun setAutoInstallAfterDownload(auto: Boolean) {
        installation.value = installation.value.copy(autoInstallAfterDownload = auto)
    }

    override suspend fun setDefaultContentKind(kind: ContentKind?) {
        search.value = search.value.copy(defaultContentKind = kind)
    }

    override suspend fun setMuteDownloadNotifications(mute: Boolean) {
        notifications.value = notifications.value.copy(muteDownloadComplete = mute)
    }

    override suspend fun setMuteInstallNotifications(mute: Boolean) {
        notifications.value = notifications.value.copy(muteInstallResult = mute)
    }

    override suspend fun setMuteStoreAlerts(mute: Boolean) {
        notifications.value = notifications.value.copy(muteStoreAlerts = mute)
    }

    override suspend fun setDiagnosticsLogEnabled(enabled: Boolean) {
        diagnostics.value = DiagnosticsSettings(enabled)
    }

    override suspend fun setMeteredNetworkAllowed(allowed: Boolean) {
        network.value = network.value.copy(meteredNetworkAllowed = allowed)
    }

    override suspend fun setChallengeStrategy(strategy: ChallengeStrategy) {
        network.value = network.value.copy(challengeStrategy = strategy)
    }

    override suspend fun setBlockUserAssistedChallenge(block: Boolean) {
        network.value = network.value.copy(blockUserAssistedChallenge = block)
    }

    override suspend fun setAllowWebAds(allow: Boolean) {
        network.value = network.value.copy(allowWebAds = allow)
    }
}

/**
 * The remote configuration, in memory.
 *
 * It records **whether** an update was asked for, not only what came back: the case that counts is
 * the button pressed with the channel off, where the thing to prove is that no request goes out.
 */
class FakeRemoteConfigRepository(
    status: RemoteConfigStatus = RemoteConfigStatus(),
) : RemoteConfigRepository {

    override val status = MutableStateFlow(status)

    /** `true` = the user switched the channel off: both methods answer `null`. */
    var blocked: Boolean = false

    /** What a genuinely started attempt returns. */
    var attempt: FetchAttempt? = null

    var staleChecks: Int = 0
        private set
    var manualRefreshes: Int = 0
        private set

    override suspend fun refreshIfStale(): FetchAttempt? {
        if (blocked) return null
        staleChecks++
        return attempt
    }

    override suspend fun refreshNow(): FetchAttempt? {
        if (blocked) return null
        manualRefreshes++
        return attempt
    }
}

/** A single listing, the one the test set. */
class FakeAppDetailRepository(detail: AppDetail? = null) : AppDetailRepository {

    val details = MutableStateFlow(detail)
    var refreshes = 0
        private set

    /**
     * How long the refresh takes to return.
     *
     * It serves to prove the difference between "it is not there" and "it is not there **yet**":
     * opening the listing of a remote store never visited, Room does not have the row and the refresh
     * is in flight. With an instantaneous refresh that window does not exist and the test cannot look
     * inside it.
     */
    var refreshDelay: Duration = Duration.ZERO

    /** What the refresh answers. It serves whoever proves what happens when a store stays silent. */
    var refreshResult: Outcome<Unit> = Outcome.Success(Unit)

    val refreshed = mutableListOf<Triple<StoreId, StoreAppRef, Boolean>>()

    /**
     * What the refresh writes **to disk**, without the flow saying so.
     *
     * It reproduces what Room really does and what no instantaneous double can show: the write is
     * finished, and the invalidation reaches the flow on another thread an instant later. In that
     * window the listing is there and the flow still emits `null` — and it is the window in which the
     * screen wrote "app not found" for one frame.
     *
     * Left at `null` the double behaves as before: disk and flow say the same thing.
     */
    var writesOnRefresh: AppDetail? = null

    private var onDisk: AppDetail? = null

    override fun observe(storeId: StoreId, ref: StoreAppRef): Flow<AppDetail?> = details

    override suspend fun detail(storeId: StoreId, ref: StoreAppRef): AppDetail? =
        onDisk ?: details.value

    override suspend fun refresh(storeId: StoreId, ref: StoreAppRef, force: Boolean): Outcome<Unit> {
        refreshes++
        refreshed += Triple(storeId, ref, force)
        if (refreshDelay > Duration.ZERO) delay(refreshDelay)
        writesOnRefresh?.let { onDisk = it }
        return refreshResult
    }

    /** What the history answers, and how many times it was asked. */
    var historyResult: Outcome<Unit> = Outcome.Success(Unit)
    var historyLoads = 0
        private set

    /**
     * How long the history takes to return.
     *
     * It serves to look inside the "I am asking" state, which without a delay lasts zero: the section
     * would open and already be finished, and the test could not tell "it never loaded" from "it
     * loaded immediately".
     */
    var historyDelay: Duration = Duration.ZERO

    override suspend fun loadVersionHistory(storeId: StoreId, ref: StoreAppRef): Outcome<Unit> {
        historyLoads++
        if (historyDelay > Duration.ZERO) delay(historyDelay)
        return historyResult
    }
}

/**
 * The download queue, with no network and no worker.
 *
 * `awaitCompletion` does not wait: it returns [completion], because what the ViewModels' tests have
 * to prove is how the outcome is reacted to, not that the waiting works — that has its own tests in
 * `:core:data`.
 */
class FakeDownloadRepository : DownloadRepository {

    val active = MutableStateFlow<List<DownloadStatus>>(emptyList())

    /** The resolutions the rows were queued with: url, headers, expected size. */
    val resolutions = mutableListOf<DownloadResolution.Direct>()
    val started = mutableListOf<Long>()

    /**
     * Who started waiting for a non-metered network.
     *
     * Separate from [started] and not a field of a pair: the question "did it start?" and the question
     * "did it start by itself?" have different readers, and mixing them would force every test looking
     * at the first to name the second.
     */
    val startedUnmetered = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()

    /** Who was closed as installed: the rows whose file went and whose history entry stayed. */
    val discarded = mutableListOf<Long>()

    /** Who had their staged file deleted without being installed: the Downloads screen's Delete. */
    val deleted = mutableListOf<Long>()

    /** Who was queued asking for an installation to follow, and who was not. */
    val pendingInstalls = mutableListOf<Long>()

    /**
     * Whether [claimPendingInstall] hands the right over.
     *
     * A field and not always `true` because the interesting case is the one where it says no: two
     * candidates for the same file, of which exactly one may proceed.
     */
    var claimSucceeds: Boolean = true
    val claims = mutableListOf<Long>()

    var historyPruned = 0
    var historyCleared = 0

    var nextId = 1L
    var completion: Outcome<File> = Outcome.Failure(AppError.NotFound)
    var expectedHash: Sha256? = null

    /**
     * How long [awaitCompletion] takes to return.
     *
     * It serves whoever proves that two concurrent operations become one: with an instantaneous wait
     * the first is already finished when the second arrives, and the guard against the duplicate looks
     * superfluous **even when it is absent**.
     */
    var completionDelay: Duration = Duration.ZERO

    override fun observeActive(): Flow<List<DownloadStatus>> = active

    override fun observeAll(): Flow<List<DownloadStatus>> = active

    override fun observe(id: Long): Flow<DownloadStatus?> =
        active.map { rows -> rows.firstOrNull { it.id == id } }

    override suspend fun get(id: Long): DownloadStatus? = active.value.firstOrNull { it.id == id }

    override suspend fun enqueue(
        storeId: StoreId,
        ref: StoreAppRef,
        versionRef: VersionRef,
        packageName: String?,
        listingId: Long?,
        resolution: DownloadResolution.Direct,
        pendingInstall: Boolean,
    ): Long {
        val id = nextId++
        resolutions += resolution
        if (pendingInstall) pendingInstalls += id
        active.value = active.value + DownloadStatus(
            id = id,
            storeId = storeId,
            ref = ref,
            versionRef = versionRef,
            packageName = packageName,
            state = DownloadState.QUEUED,
            bytesDownloaded = 0,
            bytesTotal = resolution.expectedSize,
            file = null,
            error = null,
            pendingInstall = pendingInstall,
        )
        return id
    }

    override fun observeFor(storeId: StoreId, ref: StoreAppRef): Flow<DownloadStatus?> =
        active.map { rows -> rows.firstOrNull { it.storeId == storeId && it.ref == ref } }

    override suspend fun run(id: Long): Outcome<File> = completion

    override suspend fun start(id: Long, requireUnmetered: Boolean) {
        started += id
        if (requireUnmetered) startedUnmetered += id
    }

    override suspend fun awaitCompletion(id: Long): Outcome<File> {
        if (completionDelay > Duration.ZERO) delay(completionDelay)
        return completion
    }

    override suspend fun cancel(id: Long) {
        cancelled += id
    }

    override suspend fun recordInstalled(id: Long) {
        discarded += id
        active.value = active.value.filterNot { it.id == id }
    }

    /** Who called `retire`: the counterpart of [discarded] with `keep_apk_after_install` on. */
    val retired = mutableListOf<Long>()

    override suspend fun retire(id: Long) {
        retired += id
        active.value = active.value.filterNot { it.id == id }
    }

    override suspend fun deleteStaged(id: Long) {
        deleted += id
        active.value = active.value.filterNot { it.id == id }
    }

    override suspend fun claimPendingInstall(id: Long): Boolean {
        claims += id
        return claimSucceeds
    }

    override suspend fun pruneHistory(): Int {
        historyPruned++
        return 0
    }

    override suspend fun clearHistory(): Int {
        historyCleared++
        return 0
    }

    override suspend fun requeueInterrupted() = Unit

    override suspend fun expectedHash(id: Long): Sha256? = expectedHash

    /** Puts a row into the wanted state: it serves to prove a screen reattaching. */
    fun put(status: DownloadStatus) {
        active.value = active.value.filterNot { it.id == status.id } + status
    }
}

/** The apps installed through MultiStore, in memory. */
class FakeInstalledAppsRepository(apps: List<InstalledApp> = emptyList()) : InstalledAppsRepository {

    val installed = MutableStateFlow(apps)

    /** What the `PackageManager` would say: key = packageName. */
    val onDevice = mutableMapOf<String, InstalledPackage>()

    var reconciliations = 0
        private set
    val forgotten = mutableListOf<String>()
    val ignored = mutableListOf<Pair<String, Boolean>>()
    val pinned = mutableListOf<Pair<String, Long?>>()
    val channels = mutableListOf<Triple<String, StoreId, StoreAppRef>>()

    /** `false` makes [setUpdateChannel] fail, as a listing not in the catalogue would. */
    var channelResolvable: Boolean = true

    override fun observe(): Flow<List<InstalledApp>> = installed

    override suspend fun get(packageName: String): InstalledApp? =
        installed.value.firstOrNull { it.packageName == packageName }

    override suspend fun all(): List<InstalledApp> = installed.value

    override suspend fun forListing(storeId: StoreId, ref: StoreAppRef): InstalledApp? =
        installed.value.firstOrNull {
            (it.sourceStoreId == storeId && it.sourceRef == ref) ||
                (it.updateChannelStoreId == storeId && it.updateChannelRef == ref)
        }

    override suspend fun installedPackage(packageName: String): InstalledPackage? =
        onDevice[packageName]

    override suspend fun reconcile() {
        reconciliations++
        // The same rule as the real one: what is no longer on the device disappears.
        if (onDevice.isNotEmpty()) {
            installed.value = installed.value.filter { it.packageName in onDevice }
        }
    }

    override suspend fun record(
        packageName: String,
        label: String,
        storeId: StoreId,
        ref: StoreAppRef,
        listingId: Long?,
        apkSha256: Sha256?,
        installerKind: InstallerKind,
    ) = Unit

    override suspend fun forget(packageName: String) {
        forgotten += packageName
        installed.value = installed.value.filterNot { it.packageName == packageName }
    }

    override suspend fun setIgnoreUpdates(packageName: String, ignore: Boolean) {
        ignored += packageName to ignore
        // The double writes **and** re-emits: a screen observing the list has to see the switch change,
        // otherwise the test would pass even with a UI that never updates.
        update(packageName) { it.copy(ignoreUpdates = ignore) }
    }

    override suspend fun setPinnedVersionCode(packageName: String, versionCode: Long?) {
        pinned += packageName to versionCode
        update(packageName) { it.copy(pinnedVersionCode = versionCode) }
    }

    override suspend fun setUpdateChannel(
        packageName: String,
        storeId: StoreId,
        ref: StoreAppRef,
    ): Boolean {
        if (!channelResolvable) return false
        channels += Triple(packageName, storeId, ref)
        update(packageName) { it.copy(updateChannelStoreId = storeId, updateChannelRef = ref) }
        return true
    }

    private fun update(packageName: String, change: (InstalledApp) -> InstalledApp) {
        installed.value = installed.value.map {
            if (it.packageName == packageName) change(it) else it
        }
    }
}

/**
 * What there is to update, decided by the test.
 *
 * The double recomputes nothing: the real rule lives in `VersionSelection`, and is proven where it
 * lives. Here all that is needed is being able to say "these apps have an update" and see what the
 * screen does with it.
 */
class FakeUpdateRepository(updates: List<InstalledAppUpdate> = emptyList()) : UpdateRepository {

    val state = MutableStateFlow(updates)

    var checks = 0
        private set
    val forcedChecks = mutableListOf<Boolean>()
    var report: UpdateCheckReport = UpdateCheckReport(checked = 0)

    /**
     * How long the check takes to return.
     *
     * It serves to prove that two checks together become one: with an instantaneous check the first is
     * already finished when the second arrives, and the guard against the duplicate looks superfluous
     * **even when it is absent**. It is the same reason [FakeAppDetailRepository.refreshDelay] exists.
     */
    var checkDelay: Duration = Duration.ZERO

    override fun observeAll(): Flow<List<InstalledAppUpdate>> = state

    override fun observeAvailable(): Flow<List<InstalledAppUpdate>> =
        state.map { all -> all.filter { it.available != null } }

    override suspend fun all(): List<InstalledAppUpdate> = state.value

    override suspend fun check(force: Boolean): UpdateCheckReport {
        checks++
        forcedChecks += force
        if (checkDelay > Duration.ZERO) delay(checkDelay)
        return report
    }
}

/** Installs and uninstalls, emitting the steps the test decided on. */
class FakeInstallRepository : InstallRepository {

    val plans = mutableListOf<InstallPlan>()
    val uninstalled = mutableListOf<String>()

    var installSteps: List<InstallStep> = emptyList()
    var uninstallSteps: List<InstallStep> = emptyList()

    /** What this fake device offers. By default only the system confirmation. */
    var availability: InstallerAvailability = InstallerAvailability(
        supported = setOf(InstallerKind.SESSION),
        usable = setOf(InstallerKind.SESSION),
        silent = emptySet(),
    )

    val permissionRequests = mutableListOf<InstallerKind>()
    var permissionGranted: Boolean = false

    override suspend fun installerAvailability(): InstallerAvailability = availability

    override suspend fun requestInstallerPermission(kind: InstallerKind): Boolean {
        permissionRequests += kind
        return permissionGranted
    }

    override fun install(plan: InstallPlan): Flow<InstallStep> {
        plans += plan
        return flowOf(*installSteps.toTypedArray())
    }

    override fun uninstall(packageName: String): Flow<InstallStep> {
        uninstalled += packageName
        return flowOf(*uninstallSteps.toTypedArray())
    }

    var abandonedSessions: Int = 0
    var reconciliations: Int = 0
        private set

    override suspend fun reconcileAbandonedSessions(): Int {
        reconciliations++
        return abandonedSessions
    }
}
