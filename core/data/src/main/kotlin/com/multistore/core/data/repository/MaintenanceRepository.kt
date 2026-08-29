package com.multistore.core.data.repository

import android.content.Context
import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.DatabaseMaintenance
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.database.dao.DownloadDao
import com.multistore.core.database.dao.IndexDao
import com.multistore.core.model.StorageLevel
import com.multistore.core.model.StorageUsage
import com.multistore.core.network.http.StoreHttpClients
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The maintenance operations: how much each level occupies, and how to free it.
 *
 * It exists for an architectural reason and a substantive one. The architectural one: `VACUUM`, the
 * HTTP cache and the image cache live in three different modules and no `:feature:*` can see even one
 * of them — `checkDependencyRules` forbids it, and rightly, because `:core:data` is the only place
 * where Room, DataStore, the network and the adapters meet.
 *
 * The substantive one: **maintenance is not a setting.** There is nothing to remember between
 * launches in "empty", so there is no field in `settings.proto` — a field nobody reads back would be
 * the hidden state `SettingsCoverageTest` exists to catch. What *is* a setting — the images' cap, how
 * long to keep an expired listing, whether to throw the APK away after installation — lives in the
 * proto and goes through [SettingsRepository].
 */
interface MaintenanceRepository {

    /**
     * How much each level occupies, right now.
     *
     * Suspending and not observable: none of the four directories notifies when it changes, so
     * "observing" them would mean re-measuring them at intervals — i.e. reading a whole tree of files
     * periodically for a number the user looks at for two seconds.
     */
    suspend fun usage(): StorageUsage

    /**
     * Empties **one** level and says how much it freed.
     *
     * One at a time and not all together, because the four cost very different rebuilds: refilling
     * the icons is a few hundred kilobytes on the first scroll, refilling F-Droid's catalogue is 18 MB
     * compressed to re-download.
     */
    suspend fun clear(level: StorageLevel): SpaceReclaimed

    /**
     * Compacts the database and returns the bytes freed.
     *
     * It deletes nothing visible: no row disappears, only the space SQLite was keeping aside to reuse.
     */
    suspend fun reclaimSpace(): SpaceReclaimed

    /**
     * The automatic purge: the listings expired for longer than the chosen retention, and the staged
     * APKs no row claims any more.
     *
     * It is called at startup. **It does not compact**, and that is deliberate: see
     * `DatabaseMaintenance`.
     */
    suspend fun purgeStale(): StalePurged
}

/** How much it took before and after. */
data class SpaceReclaimed(val bytesBefore: Long, val bytesAfter: Long) {
    val freedBytes: Long get() = (bytesBefore - bytesAfter).coerceAtLeast(0L)
}

/** What the automatic purge removed. */
data class StalePurged(val listings: Int, val stagedFiles: Int, val freedBytes: Long)

@Singleton
internal class MaintenanceRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val maintenance: DatabaseMaintenance,
    private val catalogDao: CatalogDao,
    private val indexDao: IndexDao,
    private val downloadDao: DownloadDao,
    private val registry: StoreRegistry,
    private val settings: SettingsRepository,
    private val httpClients: StoreHttpClients,
    private val imageCache: ImageCache,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : MaintenanceRepository {

    override suspend fun usage(): StorageUsage = withContext(io) {
        StorageUsage(
            catalogBytes = maintenance.sizeBytes(),
            imagesBytes = imageCache.sizeBytes(),
            pagesBytes = httpClients.httpCacheBytes(),
            stagedApkBytes = stagedBytes(),
            stagedReadyToInstall = downloadDao.countReadyToInstall(),
        )
    }

    /**
     * What `files/staging` occupies: the downloads **and** what has been unpacked out of them.
     *
     * It walks the directories instead of counting only top-level files, and the two are not the
     * same number: opening a container leaves a folder with base and splits inside — 250 MB on
     * Firefox — which a `filter { isFile }` would report as zero. It also has to agree with what
     * the button that empties it deletes, or the row would announce a saving that never arrives.
     */
    private fun stagedBytes(): Long = Staging.entries(context).sumOf { entry ->
        if (entry.isDirectory) {
            entry.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        } else {
            entry.length()
        }
    }

    override suspend fun clear(level: StorageLevel): SpaceReclaimed = withContext(io) {
        val before = usage().bytesOf(level)
        when (level) {
            StorageLevel.CATALOG -> clearCatalog()
            StorageLevel.IMAGES -> imageCache.clear()
            StorageLevel.PAGES -> httpClients.clearHttpCache()
            StorageLevel.STAGED_APKS -> clearStagedApks()
        }
        SpaceReclaimed(bytesBefore = before, bytesAfter = usage().bytesOf(level))
    }

    override suspend fun reclaimSpace(): SpaceReclaimed = maintenance.compact().let {
        SpaceReclaimed(bytesBefore = it.bytesBefore, bytesAfter = it.bytesAfter)
    }

    override suspend fun purgeStale(): StalePurged = withContext(io) {
        val retention = settings.storage.first().catalogRetention
        val listings = retention.duration?.let { window ->
            catalogDao.deletePurgeableListings(
                indexedStores = registry.indexedStores.map { it.id },
                expiresBeforeMillis = (clock.now() - window).toEpochMilliseconds(),
            )
        } ?: 0
        if (listings > 0) catalogDao.deleteOrphanApps()

        // Only the true orphans: a file *any* row claims stays, including those in `DONE` the user
        // asked to keep. What is left ownerless is a download whose row has gone — and MultiStore's
        // own APK, which never had a row (see `InstallSelfUpdateUseCase`: the process dies in the
        // middle of the commit, so after installation there is nobody left who could delete it).
        val orphans = sweepStaging(downloadDao.claimedFilePaths())
        StalePurged(listings = listings, stagedFiles = orphans.first, freedBytes = orphans.second)
    }

    /**
     * Throws away the downloaded catalogue: the local index, the listings, and what depends on them.
     *
     * ### Four things and not one, and the fourth is the one that gets forgotten
     *
     * 1. the **listings**, with the same five protections as the automatic purge (see
     *    `CatalogDao.deletePurgeableListings`): an installed app's update channel is not cache, and
     *    detaching it to free two kilobytes would be worse than the problem;
     * 2. the **aggregated apps** left with no listing;
     * 3. the **index entries**, which are 57% of the measured occupancy;
     * 4. the **index's state**. Without it, the next sync would find a token and would ask for a
     *    **diff** against a document that no longer exists: it would apply differences to nothing and
     *    declare itself up to date, leaving an incomplete catalogue forever and with no error
     *    anywhere. It is the costliest of the four faults and the only one that does not show
     *    immediately.
     *
     * ### Why it compacts, when the automatic purge does not
     *
     * SQLite does not give the freed pages back by itself: without `VACUUM` a button deleting 60 MB
     * would leave the file **exactly** as big as it was, and the screen would report the same number
     * as before. It is not an imprecision, it is a button declaring it has done something it has not
     * — the same family as the defect about the `VACUUM`/checkpoint order.
     */
    private suspend fun clearCatalog() {
        catalogDao.deletePurgeableListings(indexedStores = emptyList(), expiresBeforeMillis = Long.MAX_VALUE)
        catalogDao.deleteOrphanApps()
        indexDao.clearAllEntries()
        indexDao.clearAllTaxonomy()
        indexDao.clearAllState()
        maintenance.compact()
    }

    /**
     * Throws away the staged APKs no transfer that is still going to move is using.
     *
     * ### What it spares, and the bug that was in the definition
     *
     * It spares [DownloadDao.transferringFilePaths]: queued, running, paused, verifying, installing.
     * It used to spare `state NOT IN ('DONE', 'FAILED')`, which also covers `READY` — a download
     * that has **finished** and that nobody installed. Those are exactly the files this button's
     * own description promises to delete, and exactly the ones its size counts: the symptom was a
     * row saying "over 100 MB" above a button answering "there was nothing to free", every time.
     * The two halves disagreed because they read two different definitions of "in use".
     *
     * ### The rows lose their file and stay
     *
     * `forgetSettledFiles` runs **after** the files and not before: if the process died in between,
     * a row with no file would be a listing offering "Install" on nothing, whereas a file with no
     * row is picked up by the next sweep. And it forgets the file rather than deleting the row,
     * because since M5/7 the row is the history entry — deleting it would throw away the record of
     * a download to free the few hundred bytes of a row.
     */
    private suspend fun clearStagedApks() {
        sweepStaging(downloadDao.transferringFilePaths())
        downloadDao.forgetSettledFiles(clock.now())
    }

    /**
     * @return how many files and how many bytes.
     *
     * It looks at **files and directories**, and the second half is not theoretical: opening a
     * container leaves in staging a directory with base and splits inside, and a sweep filtering on
     * `isFile` would ignore it forever — on Firefox that is 250 MB nobody ever deletes. A directory
     * is protected when the download it comes from is, and the correspondence between the two is
     * known by [Staging] and not by this function.
     */
    private fun sweepStaging(keep: List<String>): Pair<Int, Long> {
        val protected = keep.toSet()
        var files = 0
        var bytes = 0L
        for (entry in Staging.entries(context)) {
            if (entry.absolutePath in protected) continue
            if (entry.isDirectory && Staging.ownerOf(entry)?.absolutePath in protected) continue
            // `filter { isFile }` and not the sum of everything the tree contains: a directory has a
            // length of its own — 128 bytes on this filesystem — and adding it would slightly inflate
            // the number the screen shows as "freed". Slightly, but in the wrong direction: it is
            // space nobody recovered.
            val size = if (entry.isDirectory) {
                entry.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            } else {
                entry.length()
            }
            if (entry.deleteRecursively()) {
                files++
                bytes += size
            }
        }
        return files to bytes
    }
}
