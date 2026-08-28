package com.multistore.core.data.mapper

import com.multistore.core.common.identity.AppKeys
import com.multistore.core.common.text.TextNormalizer
import com.multistore.core.database.dao.ListingRow
import com.multistore.core.database.dao.ListingWithDetails
import com.multistore.core.database.dao.ListingWrite
import com.multistore.core.database.entity.AppEntity
import com.multistore.core.database.entity.AppVersionEntity
import com.multistore.core.database.entity.ListingScreenshotEntity
import com.multistore.core.database.entity.StoreListingEntity
import com.multistore.core.model.AntiFeature
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ContentKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.MatchMethod
import com.multistore.core.model.Screenshot
import com.multistore.core.model.ScreenshotKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * From a domain listing to database rows.
 *
 * `listingId` stays 0: it is assigned by `CatalogDao.saveListing`, which **keeps** the existing one
 * if the row is already there. Recreating it would detach every installed app from its update
 * channel, which points at precisely that id.
 */
fun StoreListingDetail.toRows(now: Instant, ttl: Duration): ListingWrite {
    val summary = this.summary
    val appKey = AppKeys.of(summary.packageName, summary.title, summary.developer)
    val titleNorm = TextNormalizer.normalizeTitle(summary.title)

    return ListingWrite(
        app = AppEntity(
            appKey = appKey,
            packageName = summary.packageName,
            title = summary.title,
            titleNorm = titleNorm,
            developer = summary.developer,
            developerNorm = summary.developer?.let(TextNormalizer::normalizeTitle),
            iconUrl = summary.iconUrl,
            contentKind = summary.contentKind,
            updatedAt = now,
        ),
        listing = StoreListingEntity(
            appKey = appKey,
            storeId = summary.storeId,
            storeAppRef = summary.ref.value,
            title = summary.title,
            titleNorm = titleNorm,
            summary = summary.summary.takeIf { !it.isEmpty },
            description = description.takeIf { !it.isEmpty },
            whatsNew = whatsNew.takeIf { !it.isEmpty },
            rating = summary.rating,
            ratingCount = summary.ratingCount,
            downloadsLabel = summary.downloadsLabel,
            categories = summary.categories,
            contentKind = summary.contentKind,
            preferredSignerSha256 = preferredSignerSha256,
            license = license,
            sourceCodeUrl = sourceCodeUrl,
            issueTrackerUrl = issueTrackerUrl,
            webSiteUrl = webSiteUrl,
            changelogUrl = changelogUrl,
            authorName = authorName ?: summary.developer,
            donateUrls = donateUrls,
            matchConfidence = if (summary.packageName != null) 1.0f else INFERRED_CONFIDENCE,
            matchMethod = if (summary.packageName != null) MatchMethod.PACKAGE_NAME else MatchMethod.TITLE_DEV,
            addedAt = addedAt,
            lastUpdated = summary.lastUpdated,
            fetchedAt = now,
            ttlSeconds = ttl.inWholeSeconds,
        ),
        versions = versions.map { it.toEntity(now) },
        screenshots = screenshots.mapIndexed { index, shot ->
            ListingScreenshotEntity(
                listingId = 0,
                url = shot.url,
                kind = shot.kind.name,
                sortOrder = index,
            )
        },
    )
}

/**
 * The two rows needed to remember a listing **seen in a result list**.
 *
 * It is not an impoverished [ListingWrite]: it is a different type because it has to be written
 * differently. A results page publishes neither versions nor screenshots, so going through
 * `saveListing` — which does `clearVersions` before writing — would delete the versions of a listing
 * already read in full. Whoever writes these two rows uses `insertListingIfAbsent`.
 */
data class DiscoveredRows(val app: AppEntity, val listing: StoreListingEntity)

/**
 * From a result row to database rows, for cross-store matching.
 *
 * **`ttlSeconds = 0`, i.e. born already expired**, and that is not a fallback: what was read is a
 * list, and a list is not a listing. Opening it must re-read it from the store, and a TTL of zero is
 * how the existing stale-while-revalidate notices by itself, without a second mechanism saying "this
 * row is less true than the others".
 */
fun StoreListingSummary.toDiscoveredRows(
    appKey: String,
    confidence: Float,
    method: MatchMethod,
    now: Instant,
): DiscoveredRows {
    val titleNorm = TextNormalizer.normalizeTitle(title)
    return DiscoveredRows(
        app = AppEntity(
            appKey = appKey,
            packageName = packageName,
            title = title,
            titleNorm = titleNorm,
            developer = developer,
            developerNorm = developer?.let(TextNormalizer::normalizeTitle),
            iconUrl = iconUrl,
            contentKind = contentKind,
            updatedAt = now,
        ),
        listing = StoreListingEntity(
            appKey = appKey,
            storeId = storeId,
            storeAppRef = ref.value,
            title = title,
            titleNorm = titleNorm,
            summary = summary.takeIf { !it.isEmpty },
            rating = rating,
            ratingCount = ratingCount,
            downloadsLabel = downloadsLabel,
            categories = categories,
            contentKind = contentKind,
            authorName = developer,
            matchConfidence = confidence,
            matchMethod = method,
            lastUpdated = lastUpdated,
            fetchedAt = now,
            ttlSeconds = 0,
        ),
    )
}

/**
 * A listing's versions, ready to write.
 *
 * It exists because there are two producers: [toRows], which writes them together with the listing,
 * and the version history, which comes from a separate page and goes through
 * `CatalogDao.mergeVersions`. `listingId` stays zero in both cases — it is assigned by the DAO, which
 * is the only one that knows it.
 */
internal fun List<AppVersion>.toVersionRows(now: Instant): List<AppVersionEntity> =
    map { it.toEntity(now) }

private fun AppVersion.toEntity(now: Instant) = AppVersionEntity(
    listingId = 0,
    versionRef = ref.value,
    versionName = versionName,
    versionCode = versionCode,
    sizeBytes = sizeBytes,
    minSdk = minSdk,
    targetSdk = targetSdk,
    abis = abis,
    artifactType = artifactType,
    changelog = changelog.takeIf { !it.isEmpty },
    sha256 = sha256,
    signerSha256 = signerSha256,
    publishedAt = publishedAt,
    releaseChannels = releaseChannels.toList(),
    // Only the ids: an anti-feature's name and description arrive already localised from the store
    // and live in `store_anti_features`, one row per anti-feature instead of a copy inside every
    // version carrying it. On F-Droid that is 2,666 occurrences for some twenty identifiers.
    antiFeatures = antiFeatures.map { it.id },
    // A version is as old as the listing carrying it: the same instant, so that a version row cannot
    // come out fresher than the listing it was read from.
    fetchedAt = now,
)

/**
 * A list row, icon included.
 *
 * It exists next to [toSummary] and not in its place because the icon does not live in
 * `store_listings`: whoever holds only the listing cannot invent it, and a wrong icon in a list of
 * apps is worse than no icon.
 */
fun ListingRow.toSummary(): StoreListingSummary = listing.toSummary().copy(iconUrl = iconUrl)

fun StoreListingEntity.toSummary(): StoreListingSummary = StoreListingSummary(
    storeId = storeId,
    ref = StoreAppRef(storeAppRef),
    title = title,
    packageName = AppKeys.packageNameOrNull(appKey),
    summary = summary ?: LocalizedText.EMPTY,
    developer = authorName,
    categories = categories,
    // The type is carried by the row, not by the aggregated app: see migration 3 → 4. Before that
    // this mapper did not populate it at all, so every row read from the catalogue — the Home,
    // "browse", the F-Droid search — came out `UNKNOWN` even where the store had said otherwise.
    contentKind = contentKind,
    rating = rating,
    ratingCount = ratingCount,
    downloadsLabel = downloadsLabel,
    lastUpdated = lastUpdated,
)

/**
 * From database rows to a domain listing.
 *
 * `iconUrl` and `contentKind` come from `apps`, not from `store_listings`: they are properties of the
 * aggregated app. Whoever has only the listing to hand passes `null` and gets a listing with no icon,
 * which is better than a listing with another app's icon.
 */
fun ListingWithDetails.toDetail(app: AppEntity? = null): StoreListingDetail {
    val base = listing.toSummary().copy(
        iconUrl = app?.iconUrl,
        developer = app?.developer ?: listing.authorName,
        // The row first, then the aggregated app: `apps.content_kind` is shared across stores and the
        // last one to save wins, so it can carry `UNKNOWN` written by whoever does not publish the
        // type. The fallback stays because it covers rows saved before migration 3 → 4.
        contentKind = listing.contentKind.takeIf { it != ContentKind.UNKNOWN }
            ?: app?.contentKind ?: ContentKind.UNKNOWN,
        latestVersionName = versions.maxByOrNull { it.versionCode ?: Long.MIN_VALUE }?.versionName,
        latestVersionCode = versions.mapNotNull { it.versionCode }.maxOrNull(),
    )
    return StoreListingDetail(
        summary = base,
        description = listing.description ?: LocalizedText.EMPTY,
        whatsNew = listing.whatsNew ?: LocalizedText.EMPTY,
        screenshots = screenshots.sortedBy { it.sortOrder }.map {
            Screenshot(
                url = it.url,
                kind = runCatching { ScreenshotKind.valueOf(it.kind) }.getOrDefault(ScreenshotKind.PHONE),
            )
        },
        versions = versions.map { it.toModel() },
        preferredSignerSha256 = listing.preferredSignerSha256,
        license = listing.license,
        sourceCodeUrl = listing.sourceCodeUrl,
        issueTrackerUrl = listing.issueTrackerUrl,
        webSiteUrl = listing.webSiteUrl,
        changelogUrl = listing.changelogUrl,
        donateUrls = listing.donateUrls,
        authorName = listing.authorName,
        addedAt = listing.addedAt,
    )
}

fun AppVersionEntity.toModel(): AppVersion = AppVersion(
    versionName = versionName,
    versionCode = versionCode,
    ref = VersionRef(versionRef),
    artifactType = artifactType,
    sizeBytes = sizeBytes,
    minSdk = minSdk,
    targetSdk = targetSdk,
    abis = abis,
    sha256 = sha256,
    signerSha256 = signerSha256,
    publishedAt = publishedAt,
    changelog = changelog ?: LocalizedText.EMPTY,
    // Only the id: whoever shows the listing resolves name and description from the store's taxonomy.
    antiFeatures = antiFeatures.map { AntiFeature(id = it) },
    releaseChannels = releaseChannels.toSet(),
)

/** `true` if the row is past its TTL and needs refreshing in the background. */
fun StoreListingEntity.isStale(now: Instant): Boolean =
    now.epochSeconds - fetchedAt.epochSeconds >= ttlSeconds

private const val INFERRED_CONFIDENCE = 0.6f
