package com.multistore.store.an1.parser

import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.store.an1.An1Config
import com.multistore.store.an1.An1Refs
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.parseHtml

/**
 * an1's listing page, which is entirely `schema.org/MobileApplication` microdata.
 *
 * That is the most solid part of this store: name, version, size, minimum SDK, publisher,
 * description, category, update date and rating all sit in microdata attributes, written for
 * search engines rather than for the theme, so a visual restyle does not touch them.
 *
 * an1 publishes **one** file per listing and no version code anywhere, so the version produced
 * here is a single one carrying a name but no code. The size (`79.9Mb`) is rounded to one decimal
 * and is for display only; the exact value comes from the `Content-Length` of the `HEAD` on the
 * CDN, together with the SHA-256.
 */
internal class An1DetailParser(private val config: An1Config) {

    fun parse(html: String, url: String, ref: StoreAppRef): StoreResult<StoreListingDetail> =
        parseHtml(html, url) { document ->
            val selectors = config.selectors
            val title = document.attrOrNull(selectors.detailName, CONTENT)
                ?: document.text(selectors.detailName)
            val category = document.attrOrNull(selectors.detailCategory, CONTENT)
            val subCategory = document.attrOrNull(selectors.detailSubCategory, CONTENT)
            val versionName = document.textOrNull(selectors.detailVersion)
            val sizeBytes = TextValues.byteSize(document.textOrNull(selectors.detailSize))

            val summary = StoreListingSummary(
                storeId = StoreId.AN1,
                ref = ref,
                title = title,
                packageName = null,
                summary = LocalizedText.EMPTY,
                developer = document.textOrNull(selectors.detailDeveloper),
                iconUrl = document.absUrlOrNull(selectors.detailIcon, SRC),
                categories = listOfNotNull(subCategory ?: category),
                contentKind = An1Refs.contentKindOf(category),
                latestVersionName = versionName,
                // It does not exist, and is not a field we failed to find: an1 does not publish it.
                latestVersionCode = null,
                rating = TextValues.rating(document.textOrNull(selectors.detailRatingValue)),
                ratingCount = document.textOrNull(selectors.detailRatingCount)
                    ?.filter(Char::isDigit)
                    ?.toIntOrNull(),
                lastUpdated = TextValues.isoInstant(
                    document.attrOrNull(selectors.detailUpdated, DATETIME),
                ),
            )

            StoreListingDetail(
                summary = summary,
                description = LocalizedText.of(document.textOrNull(selectors.detailDescription)),
                // No screenshots anywhere on the site: verified on a program listing and a game
                // listing, where they would naturally be.
                screenshots = emptyList(),
                versions = listOfNotNull(versionOf(document, versionName, sizeBytes)),
            )
        }

    private fun versionOf(
        document: HtmlPage,
        versionName: String?,
        sizeBytes: Long?,
    ): AppVersion? {
        if (versionName == null) return null
        return AppVersion(
            versionName = versionName,
            versionCode = null,
            ref = VersionRef(versionName),
            artifactType = ArtifactType.APK,
            sizeBytes = sizeBytes,
            // `Android 5.0`, with the number **after** the word, unlike uptodown's `Android + 5.0`.
            // The conversion to an API level lives in `TextValues`.
            minSdk = TextValues.apiLevel(
                document.textOrNull(config.selectors.detailOperatingSystem),
            ),
        )
    }

    private companion object {
        const val CONTENT = "content"
        const val SRC = "src"
        const val DATETIME = "datetime"
    }
}
