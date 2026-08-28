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
 * An in-flight transfer, plus the title of the listing it came from.
 *
 * `listingTitle` is nullable and that is not an oversight: the listing can have vanished from the
 * catalogue while the file was coming down. See the `LEFT JOIN` in [DownloadDao.observeActive].
 */
data class ActiveDownloadRow(
    @Embedded val download: DownloadEntity,
    @ColumnInfo(name = "listing_title") val listingTitle: String?,
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
        SELECT d.*, l.title AS listing_title
        FROM downloads d
        LEFT JOIN store_listings l ON l.id = d.listing_id
        WHERE d.state NOT IN ('DONE', 'FAILED')
        ORDER BY d.created_at
        """,
    )
    fun observeActive(): Flow<List<ActiveDownloadRow>>

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

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

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
     * The paths a **not yet completed** transfer claims.
     *
     * This is the set the "delete downloaded APKs" button must spare: a download in progress or
     * paused has a partial file the resume needs, and deleting it would turn a cleanup into
     * eighteen megabytes to download again.
     */
    @Query("SELECT file_path FROM downloads WHERE file_path IS NOT NULL AND state NOT IN ('DONE', 'FAILED')")
    suspend fun activeFilePaths(): List<String>

    /** The rows waiting for nothing any more: the purge has just deleted their files. */
    @Query("DELETE FROM downloads WHERE state IN ('DONE', 'FAILED')")
    suspend fun deleteSettled(): Int
}
