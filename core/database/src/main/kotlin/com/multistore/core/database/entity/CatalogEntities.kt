package com.multistore.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.ContentKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.MatchMethod
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreId
import kotlin.time.Instant

/**
 * An **aggregated** app: the same thing as seen by several stores.
 *
 * [appKey] is the `packageName` where the store publishes it, otherwise a hash of title and
 * developer. With nine stores, several of which publish no packageName, identity is inferred and
 * carries a confidence — which lives on the listing, not here.
 */
@Entity(
    tableName = "apps",
    indices = [Index("title_norm"), Index("package_name")],
)
data class AppEntity(
    @PrimaryKey @ColumnInfo(name = "app_key") val appKey: String,
    @ColumnInfo(name = "package_name") val packageName: String? = null,
    val title: String,
    /** Normalised title: lowercase, no diacritics, no "MOD APK" and no version numbers. */
    @ColumnInfo(name = "title_norm") val titleNorm: String,
    val developer: String? = null,
    @ColumnInfo(name = "developer_norm") val developerNorm: String? = null,
    @ColumnInfo(name = "icon_url") val iconUrl: String? = null,
    @ColumnInfo(name = "icon_dhash") val iconDhash: String? = null,
    @ColumnInfo(name = "content_kind") val contentKind: ContentKind = ContentKind.UNKNOWN,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

/**
 * An app as **one** store presents it.
 *
 * `UNIQUE(store_id, store_app_ref)` is the natural key: two rows for the same reference on the
 * same store would be the same page counted twice.
 */
@Entity(
    tableName = "store_listings",
    indices = [
        Index(value = ["store_id", "store_app_ref"], unique = true),
        Index("app_key"),
        Index("store_id"),
        Index("title_norm"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AppEntity::class,
            parentColumns = ["app_key"],
            childColumns = ["app_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class StoreListingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "app_key") val appKey: String,
    @ColumnInfo(name = "store_id") val storeId: StoreId,
    /** Opaque: only the adapter knows whether it is a slug, an id or a packageName. */
    @ColumnInfo(name = "store_app_ref") val storeAppRef: String,
    @ColumnInfo(name = "detail_url") val detailUrl: String? = null,
    /** The title as **that** store writes it, which is often not the others'. */
    val title: String,
    @ColumnInfo(name = "title_norm") val titleNorm: String,
    val summary: LocalizedText? = null,
    val description: LocalizedText? = null,
    @ColumnInfo(name = "whats_new") val whatsNew: LocalizedText? = null,
    val rating: Float? = null,
    @ColumnInfo(name = "rating_count") val ratingCount: Int? = null,
    @ColumnInfo(name = "downloads_label") val downloadsLabel: String? = null,
    val categories: List<String> = emptyList(),
    /**
     * App or game **according to this store**, not according to the aggregated app.
     *
     * The twin column on `apps` is not enough, for a reason only visible with nine sources:
     * `apps` is written with an `@Upsert` on `app_key`, so the last listing saved wins. Eight
     * stores out of nine do not publish the kind in their listings, so one of their listings
     * **erases** the kind F-Droid wrote for the same package — silently, and precisely while a
     * "games only" filter is reading it.
     *
     * Here instead the value belongs to the row that declared it, which is also what the census
     * measures: it is apkmody saying "game" about its own listings, not about the app in general.
     *
     * The `defaultValue` is declared **here as well** and not only in the migration, and that is
     * mandatory: `ALTER TABLE … ADD COLUMN` with `NOT NULL` demands a default, and a default that
     * exists in the database but not in the entity is the mismatch Room reports on opening, i.e.
     * on the user's device. Earlier migrations avoided the problem with nullable columns; this one
     * cannot, because "unknown kind" is a domain value and not an absence.
     */
    @ColumnInfo(name = "content_kind", defaultValue = "UNKNOWN")
    val contentKind: ContentKind = ContentKind.UNKNOWN,
    /**
     * The signer **this** store recommends for a fresh installation.
     *
     * Without it, version selection cannot tell a reproducible developer-signed build from a
     * repository-signed one — and there are 15 packages out of 4,257 where getting that wrong
     * means an update the operating system will refuse.
     */
    @ColumnInfo(name = "preferred_signer_sha256") val preferredSignerSha256: Sha256? = null,
    val license: String? = null,
    @ColumnInfo(name = "source_code_url") val sourceCodeUrl: String? = null,
    @ColumnInfo(name = "issue_tracker_url") val issueTrackerUrl: String? = null,
    @ColumnInfo(name = "web_site_url") val webSiteUrl: String? = null,
    @ColumnInfo(name = "changelog_url") val changelogUrl: String? = null,
    @ColumnInfo(name = "author_name") val authorName: String? = null,
    @ColumnInfo(name = "donate_urls") val donateUrls: List<String> = emptyList(),
    @ColumnInfo(name = "match_confidence") val matchConfidence: Float = 1.0f,
    @ColumnInfo(name = "match_method") val matchMethod: MatchMethod = MatchMethod.PACKAGE_NAME,
    @ColumnInfo(name = "added_at") val addedAt: Instant? = null,
    @ColumnInfo(name = "last_updated") val lastUpdated: Instant? = null,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Instant,
    /** **Per-row** TTL, decided by the adapter: a signed index lasts far longer than a scraped page. */
    @ColumnInfo(name = "ttl_seconds") val ttlSeconds: Long,
)

@Entity(
    tableName = "listing_screenshots",
    indices = [Index("listing_id")],
    foreignKeys = [
        ForeignKey(
            entity = StoreListingEntity::class,
            parentColumns = ["id"],
            childColumns = ["listing_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ListingScreenshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "listing_id") val listingId: Long,
    val url: String,
    val kind: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/**
 * A downloadable version.
 *
 * The unique key is `(listing_id, version_ref)` and **not** the triple
 * `(listing_id, version_code, signer)`. Two reasons, both practical:
 *
 *  1. in SQLite two `NULL`s are always **distinct** inside a `UNIQUE` index, and both
 *     `version_code` and `signer_sha256` are nullable — the triple would not prevent duplicates
 *     in exactly the cases it would be introduced for;
 *  2. `version_ref` is non-null by construction: it is the opaque reference the adapter uses to
 *     resolve the download, so it identifies exactly what will be downloaded. For F-Droid it
 *     wraps the file's SHA-256, which the index already uses as a key with 0 collisions across
 *     12,871 entries.
 */
@Entity(
    tableName = "app_versions",
    indices = [
        Index(value = ["listing_id", "version_ref"], unique = true),
        Index("listing_id"),
        Index("version_code"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = StoreListingEntity::class,
            parentColumns = ["id"],
            childColumns = ["listing_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AppVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "listing_id") val listingId: Long,
    @ColumnInfo(name = "version_ref") val versionRef: String,
    @ColumnInfo(name = "version_name") val versionName: String,
    @ColumnInfo(name = "version_code") val versionCode: Long? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long? = null,
    @ColumnInfo(name = "min_sdk") val minSdk: Int? = null,
    /**
     * **Reserved column: nobody writes it and nobody reads it, today.**
     *
     * Measured against the complete F-Droid index: `usesSdk` contains only `minSdkVersion` and
     * `targetSdkVersion`, and versions carrying `maxSdkVersion` are **0 out of 12,911**. On
     * F-Droid the field does not exist.
     *
     * It stays in the table rather than being removed because `maxSdkVersion` is a standard
     * Android manifest attribute, not one store's invention: the first adapter to publish it finds
     * it ready. While nobody populates it, `VersionSelection.isCompatible` looks only at `minSdk`
     * and the ABIs — and rightly so: filtering on an always-null column would exclude everything
     * or nothing, never the right thing.
     */
    @ColumnInfo(name = "max_sdk") val maxSdk: Int? = null,
    @ColumnInfo(name = "target_sdk") val targetSdk: Int? = null,
    val abis: List<String> = emptyList(),
    @ColumnInfo(name = "artifact_type") val artifactType: ArtifactType = ArtifactType.APK,
    val changelog: LocalizedText? = null,
    val sha256: Sha256? = null,
    @ColumnInfo(name = "signer_sha256") val signerSha256: Sha256? = null,
    @ColumnInfo(name = "published_at") val publishedAt: Instant? = null,
    /** Empty = stable channel. Any value = a non-default channel, not to be offered. */
    @ColumnInfo(name = "release_channels") val releaseChannels: List<String> = emptyList(),
    @ColumnInfo(name = "anti_features") val antiFeatures: List<String> = emptyList(),
    @ColumnInfo(name = "fetched_at") val fetchedAt: Instant,
)

/** The user's manual correction of a wrong match between a listing and an app. */
@Entity(tableName = "identity_overrides")
data class IdentityOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "listing_id") val listingId: Long,
    @ColumnInfo(name = "app_key") val appKey: String,
    val action: String,
)
