package com.multistore.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.multistore.core.model.DownloadState
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreId
import kotlin.time.Instant

/**
 * An app installed **through MultiStore**.
 *
 * That is the perimeter, by choice: "My apps" does not list what the user installed elsewhere.
 *
 * [updateChannelListingId] is what makes update handling correct in a multi-store context: an app
 * taken from one store is updated **from that same store**. The first store with a higher
 * versionCode would almost always have a different signer, and the update would fail at OS level.
 *
 * A note on [signerSha256]: it is what was installed **when we installed it**. For a security
 * decision — whether to offer an update — the authoritative source stays the `PackageManager`,
 * which knows what is there now. This column exists to recognise that something changed under us,
 * not to replace that read.
 */
@Entity(tableName = "installed_apps", indices = [Index("source_store_id")])
data class InstalledAppEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_key") val appKey: String? = null,
    val label: String,
    @ColumnInfo(name = "source_store_id") val sourceStoreId: StoreId? = null,
    @ColumnInfo(name = "source_ref") val sourceRef: String? = null,
    @ColumnInfo(name = "installed_version_name") val installedVersionName: String,
    @ColumnInfo(name = "installed_version_code") val installedVersionCode: Long,
    @ColumnInfo(name = "installed_signer_sha256") val installedSignerSha256: Sha256? = null,
    @ColumnInfo(name = "installed_apk_sha256") val installedApkSha256: Sha256? = null,
    @ColumnInfo(name = "installed_at") val installedAt: Instant,
    @ColumnInfo(name = "installer_kind") val installerKind: InstallerKind = InstallerKind.SESSION,
    @ColumnInfo(name = "update_channel_listing_id") val updateChannelListingId: Long? = null,
    @ColumnInfo(name = "ignore_updates") val ignoreUpdates: Boolean = false,
    @ColumnInfo(name = "pinned_version_code") val pinnedVersionCode: Long? = null,
)

/**
 * A download, from the queue to installation.
 *
 * [filePath] is the file in staging. We generate the name rather than taking it from the store: a
 * name chosen by a server is a path chosen by a server.
 */
@Entity(tableName = "downloads", indices = [Index("state"), Index("version_ref")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "listing_id") val listingId: Long,
    /**
     * Store and reference are **on the row**, not derived from the listing with a join.
     *
     * Not gratuitous denormalisation: a download outlives the listing that produced it — a full
     * sync can delete the row of a withdrawn package while it is being downloaded — and without
     * these two fields an in-flight download would lose the only information saying who it
     * belongs to. It also tells us which User-Agent to resume with, without re-reading the
     * catalogue on every buffer.
     */
    @ColumnInfo(name = "store_id") val storeId: StoreId,
    @ColumnInfo(name = "store_app_ref") val storeAppRef: String,
    @ColumnInfo(name = "version_ref") val versionRef: String,
    @ColumnInfo(name = "package_name") val packageName: String?,
    val state: DownloadState = DownloadState.QUEUED,
    @ColumnInfo(name = "bytes_downloaded") val bytesDownloaded: Long = 0,
    @ColumnInfo(name = "bytes_total") val bytesTotal: Long? = null,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    @ColumnInfo(name = "resolved_url") val resolvedUrl: String? = null,
    /**
     * The validator the server gave on the first attempt: `ETag`, or `Last-Modified`.
     *
     * Needed for resuming. A `Range` without `If-Range` asks "give me from here on" without saying
     * "of that file": if the server has published a different version in the meantime, the new
     * bytes are glued onto the old ones and the resulting file is neither. With `If-Range`, a
     * server whose content changed answers 200 with the whole file, and the resume turns itself
     * into a fresh download.
     */
    @ColumnInfo(name = "validator") val validator: String? = null,
    /**
     * The headers without which *that* server answers 403, captured at resolution time.
     *
     * They are on the row because they cannot be recomputed later: the `Referer` apkmirror's
     * `download.php` demands is the URL of the interstitial traversed back then, and the assisted
     * path's `Cookie` belongs to the session in which the user tapped on the store's page. A
     * download resuming after the process died, or starting from an update check, has no other
     * way of knowing them.
     *
     * Two adapters already computed them and they **were lost between `enqueue` and the network**:
     * `DownloadRequest` accepted them, the row did not keep them, and nobody passed them on.
     */
    @ColumnInfo(name = "request_headers") val requestHeaders: Map<String, String>? = null,
    @ColumnInfo(name = "expected_sha256") val expectedSha256: Sha256? = null,
    @ColumnInfo(name = "actual_sha256") val actualSha256: Sha256? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    /**
     * When this download was actually installed, or `null` if it never was.
     *
     * It exists because after M5/7 the row **survives** the installation instead of being deleted,
     * and without it three different histories would look the same. `DONE` with no file can mean
     * "installed and the APK thrown away" — the normal case, `keep_apk_after_install` off — or
     * "downloaded and then deleted without ever being installed", which is what the Downloads
     * screen's Delete button and the storage cleanup produce. The state cannot tell them apart;
     * this can, and it is also the date the history row shows.
     *
     * Nullable and without a `DEFAULT`, the same choice as migrations 1 → 2 and 2 → 3: `null`
     * means "not installed", which is also true of every row written before this version.
     */
    @ColumnInfo(name = "installed_at") val installedAt: Instant? = null,
    /**
     * An installation was meant to follow this download, and has not happened yet.
     *
     * It is what separates "the user pressed Install and then walked away" from "the periodic
     * check downloaded this to be installed later, because `auto_install_updates` is off". Both
     * leave the row in `READY` with a whole file, and only the first may be carried on to the
     * system's confirmation by `auto_install_after_download`: honouring that switch on the second
     * would overrule a setting the user has already answered.
     *
     * It is also the **claim token** that stops the same file being installed twice. Whoever
     * decides to carry on clears it atomically with `claimPendingInstall`, so of two racing
     * candidates — the listing that is still on screen and the coordinator that watches from the
     * shell — exactly one proceeds. Clearing it is also what stops a cancelled confirmation from
     * being proposed again in a loop.
     *
     * `NOT NULL` with a default, which therefore has to be declared **twice** — here and in the
     * migration — for the reason written on `store_listings.content_kind`: a default that exists
     * in the database and not in the entity is the mismatch Room reports when opening, on the
     * user's device. `false` is the prudent value for rows written before this version: an old
     * `READY` row is not auto-installed.
     */
    @ColumnInfo(name = "pending_install", defaultValue = "0") val pendingInstall: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)
