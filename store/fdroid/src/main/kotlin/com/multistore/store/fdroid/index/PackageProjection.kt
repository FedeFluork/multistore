package com.multistore.store.fdroid.index

import com.multistore.core.model.AntiFeature
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.ContentKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.ScreenshotKind
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.fdroid.FdroidRefs
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * From an index package to the listing the rest of the app uses.
 *
 * It is a pure function of a single entry: no network, no state, no access to the rest of the index.
 * That way it is testable against a fixture and reusable after a merge patch, which is exactly what
 * `IndexedStoreAdapter.projectEntry` promises.
 *
 * The real index's traps this class has to handle, all measured:
 *
 *  - **3 entries out of 12,871 are `.zip`** (`org.fdroid.fdroid.privileged.ota`): OTAs to be
 *    flashed, not installable with `PackageInstaller`. They are also the only ones without
 *    `manifest.signer.sha256` and without `usesSdk`, and belong to the only package lacking a
 *    `preferredSigner`. Filtering by extension solves all three problems at once, and a package left
 *    with no versions is discarded entirely.
 *  - **45 non-canonical file names**: the URL always comes from `file.name`, never reconstructed.
 *  - **`metadata.antiFeatures` does not exist**: the anti-features are only in the versions.
 *  - **`versionCode` is not unique**: `juloo.keyboard2` publishes 50 twice with different
 *    signatures. Identity is the file's SHA-256, which the index already uses as the key.
 */
class PackageProjection(
    private val storeId: StoreId = StoreId.FDROID,
    /** The repository's base URL, for turning relative paths into absolute URLs. */
    private val repoUrl: String,
) {

    /**
     * @return the listing, or `null` if the package offers nothing installable.
     */
    fun project(packageName: String, pkg: JsonObject): StoreListingDetail? {
        val metadata = pkg["metadata"] as? JsonObject ?: return null
        val versions = projectVersions(pkg["versions"] as? JsonObject)
        if (versions.isEmpty()) return null

        val preferredSigner = Sha256.parseOrNull(metadata.string("preferredSigner"))
        val categories = (metadata["categories"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullIfNotString() }
            .orEmpty()

        // "The latest version" here is device-independent: default channel plus recommended signer.
        // The real choice, taking minSdk, ABI and the already-installed signature into account, is
        // made by VersionSelection when the device is known.
        val headline = versions
            .filter { it.isDefaultChannel }
            .filter { preferredSigner == null || it.signerSha256 == null || it.signerSha256 == preferredSigner }
            .maxByOrNull { it.versionCode ?: Long.MIN_VALUE }

        val summary = StoreListingSummary(
            storeId = storeId,
            ref = FdroidRefs.appRef(packageName),
            title = LocalePruning.toLocalizedText(metadata["name"]).resolve(TITLE_PREFERENCE)
                ?: packageName,
            packageName = packageName,
            summary = LocalePruning.toLocalizedText(metadata["summary"]),
            developer = metadata.string("authorName"),
            iconUrl = firstFileUrl(metadata["icon"]),
            categories = categories,
            contentKind = contentKind(categories),
            latestVersionName = headline?.versionName,
            latestVersionCode = headline?.versionCode,
            // F-Droid publishes no ratings: declaring it null here is what makes the
            // `providesRating = false` capability honest.
            rating = null,
            ratingCount = null,
            downloadsLabel = null,
            lastUpdated = metadata.epochMillis("lastUpdated"),
        )

        return StoreListingDetail(
            summary = summary,
            description = LocalePruning.toLocalizedText(metadata["description"]),
            whatsNew = versions.firstOrNull()?.changelog ?: LocalizedText.EMPTY,
            screenshots = projectScreenshots(metadata["screenshots"]),
            versions = versions,
            preferredSignerSha256 = preferredSigner,
            license = metadata.string("license"),
            sourceCodeUrl = metadata.string("sourceCode"),
            issueTrackerUrl = metadata.string("issueTracker"),
            webSiteUrl = metadata.string("webSite"),
            changelogUrl = metadata.string("changelog"),
            translationUrl = metadata.string("translation"),
            donateUrls = (metadata["donate"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullIfNotString() }
                .orEmpty(),
            authorName = metadata.string("authorName"),
            addedAt = metadata.epochMillis("added"),
        )
    }

    /**
     * The signer, **only if there is exactly one**.
     *
     * `manifest.signer.sha256` is an array because an APK can be co-signed by several certificates.
     * Measured against the real index: across 12,911 versions the array always has length one, so
     * taking the first was discarding nothing — but it was *guessing*, and in an array of two the
     * signer's identity is the set, not its first element.
     *
     * With more than one signer `null` is returned, which in `VersionSelection`'s vocabulary already
     * means "we do not know, we exclude nothing": version selection does not filter by signature and
     * the decision is left to the pre-install pipeline, which reads the real signers from the
     * downloaded APK instead of trusting the catalogue. Guessing the first could instead produce an
     * invented signature conflict — and the way out we offer for a signature conflict is "uninstall
     * and reinstall, lose your data".
     */
    private fun singleSigner(manifest: JsonObject): Sha256? {
        val signers = (manifest["signer"] as? JsonObject)?.get("sha256") as? JsonArray ?: return null
        if (signers.size != 1) return null
        return (signers.first() as? JsonPrimitive)?.contentOrNullIfNotString()?.let(Sha256::parseOrNull)
    }

    private fun projectVersions(versions: JsonObject?): List<AppVersion> {
        if (versions == null) return emptyList()
        return versions.entries.mapNotNull { (sha, element) ->
            projectVersion(sha, element as? JsonObject ?: return@mapNotNull null)
        }.sortedByDescending { it.versionCode ?: Long.MIN_VALUE }
    }

    private fun projectVersion(sha: String, version: JsonObject): AppVersion? {
        val file = version["file"] as? JsonObject ?: return null
        val fileName = file.string("name") ?: return null
        if (!fileName.endsWith(APK_SUFFIX, ignoreCase = true)) return null

        // The map's key **is** `file.sha256` on all 12,871 real entries, verified. It used to be a
        // comment; now it is a check, because the two values serve different purposes and a
        // divergence is not a detail: the `versionRef` is born from `sha256`, and it is the hash the
        // pre-install pipeline compares against the downloaded file. If key and field contradict
        // each other we do not know which file the store will serve, and an ambiguous artefact is
        // not safely installable: it is discarded. When the key is not a hash — it does not happen,
        // but the format does not forbid it — the field wins, being the explicit declaration.
        val declared = Sha256.parseOrNull(file.string("sha256"))
        val keyed = Sha256.parseOrNull(sha)
        if (declared != null && keyed != null && declared != keyed) return null
        val sha256 = declared ?: keyed ?: return null
        val size = file.long("size") ?: 0L
        val manifest = version["manifest"] as? JsonObject ?: return null
        val versionName = manifest.string("versionName") ?: return null
        val usesSdk = manifest["usesSdk"] as? JsonObject

        return AppVersion(
            versionName = versionName,
            versionCode = manifest.long("versionCode"),
            ref = FdroidRefs.versionRef(sha256, size, fileName),
            artifactType = ArtifactType.APK,
            sizeBytes = size,
            minSdk = usesSdk?.int("minSdkVersion"),
            targetSdk = usesSdk?.int("targetSdkVersion"),
            abis = (manifest["nativecode"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullIfNotString() }
                .orEmpty(),
            sha256 = sha256,
            signerSha256 = singleSigner(manifest),
            publishedAt = version.epochMillis("added"),
            changelog = LocalePruning.toLocalizedText(version["whatsNew"]),
            // Identifiers only: localised names and descriptions come from the index's `repo` block
            // and are stored once, not repeated on each of the 2,666 versions that have at least
            // one.
            antiFeatures = (version["antiFeatures"] as? JsonObject)
                ?.keys
                ?.map { AntiFeature(id = it) }
                .orEmpty(),
            releaseChannels = (version["releaseChannels"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullIfNotString() }
                ?.toSet()
                .orEmpty(),
        )
    }

    private fun projectScreenshots(element: JsonElement?): List<Screenshot> {
        val byKind = element as? JsonObject ?: return emptyList()
        return byKind.entries.flatMap { (kindKey, perLocale) ->
            val kind = SCREENSHOT_KINDS[kindKey] ?: ScreenshotKind.PHONE
            LocalePruning.localizedFileList(perLocale).mapNotNull { (_, file) ->
                file.string("name")?.let { Screenshot(url = absoluteUrl(it), kind = kind) }
            }
        }
    }

    /**
     * The icon, resolved preferring `en-US`.
     *
     * Localised icons exist (154 packages have a `de-DE` variant) but nearly always differ only in
     * the text inside the image. Choosing a single one keeps the model simple; the screenshots, where
     * the difference really matters, are all kept.
     */
    private fun firstFileUrl(element: JsonElement?): String? {
        val byLocale = element as? JsonObject ?: return null
        val preferred = TITLE_PREFERENCE.firstNotNullOfOrNull { tag ->
            byLocale.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }
        } ?: byLocale.entries.minByOrNull { it.key }
        val file = preferred?.value as? JsonObject ?: return null
        return file.string("name")?.let(::absoluteUrl)
    }

    private fun absoluteUrl(path: String): String =
        if (path.startsWith("http")) path else repoUrl.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * App or game, from the categories.
     *
     * F-Droid has no field for the distinction: it is derived from the category names, which however
     * do not all end in "Game" — `Dice`, `Emulator` and `Visual Novel` are games without saying so.
     * The explicit list covers those cases; for the rest the suffix suffices.
     */
    private fun contentKind(categories: List<String>): ContentKind = when {
        categories.isEmpty() -> ContentKind.UNKNOWN
        categories.any { it.endsWith(GAME_SUFFIX) || it in GAME_CATEGORIES } -> ContentKind.GAME
        else -> ContentKind.APP
    }

    private companion object {
        const val APK_SUFFIX = ".apk"
        const val GAME_SUFFIX = "Game"
        val GAME_CATEGORIES = setOf("Dice", "Emulator", "Visual Novel")

        /** The order a title is looked for in: `en-US` dominates the index, `en` is the fallback. */
        val TITLE_PREFERENCE = listOf("en-US", "en")

        val SCREENSHOT_KINDS = mapOf(
            "phone" to ScreenshotKind.PHONE,
            "sevenInch" to ScreenshotKind.SEVEN_INCH,
            "tenInch" to ScreenshotKind.TEN_INCH,
            "tv" to ScreenshotKind.TV,
            "wear" to ScreenshotKind.WEAR,
        )
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullIfNotString()

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.epochMillis(key: String): Instant? =
    long(key)?.takeIf { it > 0 }?.let(Instant::fromEpochMilliseconds)

private fun JsonPrimitive.contentOrNullIfNotString(): String? =
    if (isString) content.takeIf { it.isNotEmpty() } else null
