package com.multistore.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import com.multistore.core.database.entity.DownloadEntity
import com.multistore.core.database.entity.InstalledAppEntity
import com.multistore.core.model.DownloadState
import com.multistore.core.model.StoreId
import kotlinx.coroutines.flow.Flow

/**
 * An installed app as it appears in a **list**: the row plus the aggregated app's icon.
 *
 * The icon is deliberately not in `installed_apps`. What the `PackageManager` has is a `Drawable`,
 * not a URL: showing that would mean routing bitmaps through UI state, and for an app uninstalled
 * halfway down the list it would not even exist any more. The icon of the listing the app came
 * from is the same one the user saw when pressing "Install", is already in Coil's cache, and is a
 * URL — a value that can sit in immutable state without a second thought.
 *
 * `LEFT JOIN` and not `JOIN`: `app_key` is null for rows written before
 * `InstalledAppsRepository.record` resolved it, and their absence from the list would be a worse
 * defect than a placeholder.
 */
data class InstalledAppRow(
    @Embedded val app: InstalledAppEntity,
    @ColumnInfo(name = "icon_url") val iconUrl: String?,
    /**
     * The store and reference of the listing this app updates from.
     *
     * They come from the row pointed to by `update_channel_listing_id`, not from
     * `source_store_id`, and the difference is the whole point of the field: they are the same
     * thing until someone changes channel, and become two different things at the exact moment
     * someone does.
     *
     * They stay `null` when the channel is unknown, or when it points at a listing a sync has
     * deleted: no foreign key prevents that, and none should — a package withdrawn from the store
     * is no reason to forget the user has it installed.
     */
    @ColumnInfo(name = "channel_store_id") val channelStoreId: StoreId?,
    @ColumnInfo(name = "channel_ref") val channelRef: String?,
)

@Dao
interface InstalledAppDao {

    @Query(
        """
        SELECT i.*, a.icon_url AS icon_url,
               l.store_id AS channel_store_id, l.store_app_ref AS channel_ref
        FROM installed_apps AS i
        LEFT JOIN apps AS a ON a.app_key = i.app_key
        LEFT JOIN store_listings AS l ON l.id = i.update_channel_listing_id
        ORDER BY i.label COLLATE NOCASE
        """,
    )
    fun observeAll(): Flow<List<InstalledAppRow>>

    /** The same rows as [observeAll], read once: what a worker needs. */
    @Query(
        """
        SELECT i.*, a.icon_url AS icon_url,
               l.store_id AS channel_store_id, l.store_app_ref AS channel_ref
        FROM installed_apps AS i
        LEFT JOIN apps AS a ON a.app_key = i.app_key
        LEFT JOIN store_listings AS l ON l.id = i.update_channel_listing_id
        ORDER BY i.label COLLATE NOCASE
        """,
    )
    suspend fun all(): List<InstalledAppRow>

    /** The same row as [observeAll], for a single package. */
    @Query(
        """
        SELECT i.*, a.icon_url AS icon_url,
               l.store_id AS channel_store_id, l.store_app_ref AS channel_ref
        FROM installed_apps AS i
        LEFT JOIN apps AS a ON a.app_key = i.app_key
        LEFT JOIN store_listings AS l ON l.id = i.update_channel_listing_id
        WHERE i.package_name = :packageName
        """,
    )
    suspend fun row(packageName: String): InstalledAppRow?

    /**
     * The installed app matching **this listing**, if there is one.
     *
     * The inverse path to [row]: there one starts from the package, here from the listing. It is
     * the only way to answer "already installed" on the four stores that do not publish the
     * `packageName`: without the package name the system cannot be queried, but the listing the
     * app came from is known, and from there the name follows.
     *
     * It looks at **both** the provenance and the update channel: they coincide until someone
     * changes channel, and after a change the new listing is the one the user is looking at.
     */
    @Query(
        """
        SELECT i.*, a.icon_url AS icon_url,
               l.store_id AS channel_store_id, l.store_app_ref AS channel_ref
        FROM installed_apps AS i
        LEFT JOIN apps AS a ON a.app_key = i.app_key
        LEFT JOIN store_listings AS l ON l.id = i.update_channel_listing_id
        WHERE (i.source_store_id = :storeId AND i.source_ref = :ref)
           OR (l.store_id = :storeId AND l.store_app_ref = :ref)
        LIMIT 1
        """,
    )
    suspend fun forListing(storeId: StoreId, ref: String): InstalledAppRow?

    @Query("SELECT * FROM installed_apps WHERE package_name = :packageName")
    suspend fun get(packageName: String): InstalledAppEntity?

    @Query("SELECT package_name FROM installed_apps")
    suspend fun packageNames(): List<String>

    @Upsert
    suspend fun upsert(app: InstalledAppEntity)

    @Query("DELETE FROM installed_apps WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    /**
     * Removes the rows of packages no longer on the device.
     *
     * An app can vanish without going through us — uninstalled from system settings, or removed
     * with a secondary user. Without this reconciliation "My apps" would show ghosts, and the
     * update check would try to update them.
     */
    @Query("DELETE FROM installed_apps WHERE package_name NOT IN (:stillInstalled)")
    suspend fun retainOnly(stillInstalled: List<String>)

    @Query("UPDATE installed_apps SET ignore_updates = :ignore WHERE package_name = :packageName")
    suspend fun setIgnoreUpdates(packageName: String, ignore: Boolean)

    /**
     * Pins (or unpins, with `null`) the app to a `versionCode`.
     *
     * The column existed with **nobody** writing it: it survived a reinstall and could never
     * become anything other than `null`.
     */
    @Query("UPDATE installed_apps SET pinned_version_code = :versionCode WHERE package_name = :packageName")
    suspend fun setPinnedVersionCode(packageName: String, versionCode: Long?)

    /** Changes the listing this app will update from. */
    @Query("UPDATE installed_apps SET update_channel_listing_id = :listingId WHERE package_name = :packageName")
    suspend fun setUpdateChannel(packageName: String, listingId: Long?)

    /**
     * Realigns with what the device holds now.
     *
     * Needed when a package changes **without going through us**: a sideload, another store,
     * `adb install`. The three columns say what it was at the last installation we performed, and
     * on their own they would never find out — with the result that "My apps" announces a version
     * that is no longer on the phone.
     *
     * It touches neither provenance nor channel: those stay ours. What changed is the package, not
     * where it will be updated from.
     */
    @Query(
        """
        UPDATE installed_apps
        SET installed_version_name = :versionName,
            installed_version_code = :versionCode,
            installed_signer_sha256 = :signerSha256
        WHERE package_name = :packageName
        """,
    )
    suspend fun setInstalledVersion(
        packageName: String,
        versionName: String,
        versionCode: Long,
        signerSha256: String?,
    )
}

/**
 * A transfer, plus the name and icon of the app it came from.
 *
 * Both are nullable and that is not an oversight: the listing can have vanished from the catalogue
 * while the file was coming down, and a download **outlives** the row that produced it. See the two
 * `LEFT JOIN`s in [DownloadDao.observeActive].
 *
 * The icon does not come from `store_listings` — that table has no such column — but from `apps`,
 * which is where the aggregated app keeps it. Hence two joins and not one.
 */
data class ActiveDownloadRow(
    @Embedded val download: DownloadEntity,
    @ColumnInfo(name = "listing_title") val listingTitle: String?,
    @ColumnInfo(name = "icon_url") val iconUrl: String?,
)

@Dao
interface DownloadDao {

    /**
     * The in-flight transfers, **with the name of the app** that started them.
     *
     * A `LEFT JOIN` and not a second query: whoever watches this list watches it while it scrolls
     * — it is the card the app shows above every screen — and a query per row would reopen the
     * database on every frame of progress.
     *
     * `LEFT` and not `INNER` for the same reason store and ref live **on the row**: a download
     * outlives the listing that produced it, and a sync deleting a withdrawn package must not make
     * an in-flight download disappear from the list showing it. Without a title the card writes
     * the package name; without a row it would write nothing.
     */
    @Query(
        """
        SELECT d.*, l.title AS listing_title, a.icon_url AS icon_url
        FROM downloads d
        LEFT JOIN store_listings l ON l.id = d.listing_id
        LEFT JOIN apps a ON a.app_key = l.app_key
        WHERE d.state NOT IN ('DONE', 'FAILED')
        ORDER BY d.created_at
        """,
    )
    fun observeActive(): Flow<List<ActiveDownloadRow>>

    /**
     * Every download the app remembers: what is moving, what is waiting, what has finished.
     *
     * One query and not three, because the Downloads screen has to draw the three groups at the
     * same instant and a row moves between them **while it is being looked at** — a transfer ends
     * and becomes "ready to install", an installation succeeds and becomes history. Three separate
     * flows would emit at three different moments and the same app would briefly appear twice, or
     * not at all.
     *
     * **Not capped**, and that is deliberate. What bounds this list is
     * `download_history_limit`, a value the user chose and can set to "keep them all"; adding a
     * `LIMIT` here would silently contradict that choice, and the screen would look as if the
     * older rows had been pruned when they are still on disk.
     *
     * Newest first: unlike [observeActive] — whose consumer is a progress card where the order is
     * the order transfers started — this list is read as a history.
     */
    @Query(
        """
        SELECT d.*, l.title AS listing_title, a.icon_url AS icon_url
        FROM downloads d
        LEFT JOIN store_listings l ON l.id = d.listing_id
        LEFT JOIN apps a ON a.app_key = l.app_key
        ORDER BY d.created_at DESC
        """,
    )
    fun observeAll(): Flow<List<ActiveDownloadRow>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE version_ref = :versionRef AND state NOT IN ('DONE', 'FAILED')")
    suspend fun activeFor(versionRef: String): DownloadEntity?

    /**
     * The in-flight download for **this app**, if there is one.
     *
     * Needed by a screen reopened while the worker is still downloading: without it, the listing
     * would show the "Install" button again over a download already in progress, and the user
     * would see the notification say one thing and the listing another.
     *
     * `ORDER BY created_at DESC LIMIT 1`: if two non-terminal rows existed for the same app — they
     * should not, `enqueue` reuses the active one — the most recent counts, not an arbitrary one.
     */
    @Query(
        """
        SELECT * FROM downloads
        WHERE store_id = :storeId AND store_app_ref = :ref AND state NOT IN ('DONE', 'FAILED')
        ORDER BY created_at DESC
        LIMIT 1
        """,
    )
    fun observeFor(storeId: StoreId, ref: String): Flow<DownloadEntity?>

    @Upsert
    suspend fun upsert(download: DownloadEntity): Long

    @Query("UPDATE downloads SET state = :state, updated_at = :now WHERE id = :id")
    suspend fun setState(id: Long, state: DownloadState, now: kotlin.time.Instant)

    @Query(
        "UPDATE downloads SET bytes_downloaded = :bytes, bytes_total = :total, updated_at = :now WHERE id = :id",
    )
    suspend fun setProgress(id: Long, bytes: Long, total: Long?, now: kotlin.time.Instant)

    @Query("UPDATE downloads SET validator = :validator, bytes_total = :total, updated_at = :now WHERE id = :id")
    suspend fun setValidator(id: Long, validator: String?, total: Long?, now: kotlin.time.Instant)

    /**
     * Downloads left halfway by the death of the process.
     *
     * They have to be resumed or closed at startup: left as they are they occupy space and the UI
     * would show them as "in progress" forever.
     */
    @Query("SELECT * FROM downloads WHERE state IN ('RUNNING', 'VERIFYING', 'INSTALLING')")
    suspend fun interrupted(): List<DownloadEntity>

    /**
     * The already-completed row for this version, if one exists with the file still on disk.
     *
     * It exists only for `keep_apk_after_install`: with the switch off no row survives a successful
     * installation, so this query never finds anything. With it on, this is what makes the kept
     * file **reusable** rather than merely occupying space.
     *
     * `DONE` and not `isSettled`: `READY` and `PAUSED` are already caught by [activeFor], which
     * treats them as in progress; `FAILED` has no file to reuse.
     */
    @Query(
        """
        SELECT * FROM downloads
        WHERE store_id = :storeId AND store_app_ref = :ref AND version_ref = :versionRef
          AND state = 'DONE' AND file_path IS NOT NULL
        ORDER BY updated_at DESC
        LIMIT 1
        """,
    )
    suspend fun completedFor(storeId: StoreId, ref: String, versionRef: String): DownloadEntity?

    /** The paths any row claims: everything else in staging is orphaned. */
    @Query("SELECT file_path FROM downloads WHERE file_path IS NOT NULL")
    suspend fun claimedFilePaths(): List<String>

    /**
     * The paths a transfer that **is still going to move** claims.
     *
     * This is the set the "delete downloaded APKs" button must spare, and the states listed are
     * the whole of the definition: a queued, running, paused or verifying download has a partial
     * file its resume needs, and an installing one is having its bytes written into a
     * `PackageInstaller` session right now.
     *
     * ### `READY` is deliberately **not** here, and its absence is a fix
     *
     * This query used to read `state NOT IN ('DONE', 'FAILED')`, which spares `READY` — i.e. a
     * download that has finished and that nobody installed. That is precisely what the button
     * promises to delete ("downloads you never installed"), and precisely what its level's size
     * counts: the observed symptom was a row reading "over 100 MB" above a button that answered
     * "there was nothing to free", every time. The two halves disagreed because they were reading
     * two different definitions of "in use".
     */
    @Query(
        """
        SELECT file_path FROM downloads
        WHERE file_path IS NOT NULL
          AND state IN ('QUEUED', 'RUNNING', 'PAUSED', 'VERIFYING', 'INSTALLING')
        """,
    )
    suspend fun transferringFilePaths(): List<String>

    /**
     * Forgets the files the sweep has just deleted, **keeping the rows**.
     *
     * The rows are history now, so deleting them would throw away the record of a download the
     * user made — which is the thing the Downloads screen exists to show. What has to go is the
     * pointer: a `READY` row whose file no longer exists would offer "Install" over nothing.
     *
     * `READY` becomes `DONE` because the row has finished its cycle: nothing will move it again.
     * `installed_at` stays untouched, and it is what keeps the two readings apart — an installed
     * app whose kept APK was deleted, and a download deleted before it was ever installed.
     * `pending_install` is cleared for the same reason: there is no file left to carry on to.
     */
    @Query(
        """
        UPDATE downloads
        SET file_path = NULL,
            pending_install = 0,
            state = CASE WHEN state = 'READY' THEN 'DONE' ELSE state END,
            updated_at = :now
        WHERE state IN ('READY', 'DONE', 'FAILED') AND file_path IS NOT NULL
        """,
    )
    suspend fun forgetSettledFiles(now: kotlin.time.Instant): Int

    /** How many concluded downloads still hold a file: what the "empty" button would delete. */
    @Query(
        "SELECT COUNT(*) FROM downloads WHERE state = 'READY' AND file_path IS NOT NULL",
    )
    suspend fun countReadyToInstall(): Int

    /**
     * Records that this download reached the device, and closes the row.
     *
     * The counterpart of the old `delete`: the row survives the installation and becomes the
     * history entry. `file_path` goes to `NULL` because the caller has just thrown the APK away —
     * with `keep_apk_after_install` on it is `retire` that runs instead, and that one keeps both.
     */
    @Query(
        """
        UPDATE downloads
        SET state = 'DONE', file_path = NULL, pending_install = 0,
            installed_at = :at, updated_at = :at
        WHERE id = :id
        """,
    )
    suspend fun markInstalled(id: Long, at: kotlin.time.Instant)

    /**
     * Forgets **one** row's file, at the user's request, without recording an installation.
     *
     * The row survives as history and `installed_at` stays `null`: that absence is what separates
     * "deleted before it was ever installed" from "installed, and the APK deleted afterwards".
     */
    @Query(
        """
        UPDATE downloads
        SET file_path = NULL, pending_install = 0, state = 'DONE', updated_at = :now
        WHERE id = :id
        """,
    )
    suspend fun forgetFile(id: Long, now: kotlin.time.Instant)

    /** Records the installation without touching the file: `keep_apk_after_install` on. */
    @Query(
        """
        UPDATE downloads
        SET state = 'DONE', pending_install = 0, installed_at = :at, updated_at = :at
        WHERE id = :id
        """,
    )
    suspend fun markInstalledKeepingFile(id: Long, at: kotlin.time.Instant)

    /**
     * Takes the right to carry this download on to the installation, if it is still going.
     *
     * @return 1 if this caller won it, 0 if somebody already had.
     *
     * The atomicity is the whole point, and the case is real: when a download finishes there can
     * be **two** candidates — the listing still on screen, which has been awaiting it, and the
     * shell's coordinator, which watches every transfer. Both see the same row become `READY` in
     * the same instant, and two `PackageInstaller` sessions on the same file are two confirmation
     * dialogs for one app. A read followed by a write would not settle it; `UPDATE … WHERE
     * pending_install = 1` does, because SQLite decides.
     *
     * It is also what stops a loop: a confirmation the user dismisses leaves the row in `READY`
     * with the token spent, so nobody proposes it again by itself.
     */
    @Query("UPDATE downloads SET pending_install = 0 WHERE id = :id AND pending_install = 1")
    suspend fun claimPendingInstall(id: Long): Int

    /**
     * Trims the history to the last [keep] concluded downloads.
     *
     * `state IN ('DONE', 'FAILED')` **on both sides** of the subquery, and that is the part that
     * matters: a queued, running, paused or ready-to-install row is not history. Counting them
     * towards the ceiling would let a burst of live transfers push real history out; deleting one
     * would leave an APK on disk that no row claims and no button can install.
     *
     * A pruned row may still own a kept file (`keep_apk_after_install`). Its bytes are not lost:
     * the file becomes an orphan, and the startup sweep in `MaintenanceRepository.purgeStale`
     * deletes exactly those. The trade is deliberate — the alternative is teaching this query to
     * touch the filesystem.
     */
    @Query(
        """
        DELETE FROM downloads
        WHERE state IN ('DONE', 'FAILED')
          AND id NOT IN (
            SELECT id FROM downloads
            WHERE state IN ('DONE', 'FAILED')
            ORDER BY created_at DESC
            LIMIT :keep
          )
        """,
    )
    suspend fun pruneHistory(keep: Int): Int

    /**
     * Empties the history at the user's request: the concluded rows, and nothing else.
     *
     * The same `state IN ('DONE', 'FAILED')` as [pruneHistory], for the same reason. Somebody
     * clearing a list must not thereby cancel a download that is running, and must not lose the
     * row that says an APK is waiting to be installed.
     */
    @Query("DELETE FROM downloads WHERE state IN ('DONE', 'FAILED')")
    suspend fun deleteHistory(): Int
}
