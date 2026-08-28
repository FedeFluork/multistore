package com.multistore.store.uptodown.parser

import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.parseHtmlOrNotFound
import com.multistore.store.uptodown.UptodownConfig

/** The listing, plus the identity of the file the page describes. */
internal data class UptodownDetail(
    val listing: StoreListingDetail,
    /** The current version's `data-file-id`: the version list does not tell it apart on its own. */
    val currentFileId: String?,
    val currentFile: UptodownFileInfo,
)

/**
 * An app's listing page on uptodown.
 *
 * **It is the richest page among the scraped stores, and second in the whole project only to the
 * F-Droid index.** It publishes in the clear, for the current version: `packageName`, the file's
 * **SHA-256**, size, date, type, rating with review count, exact download count, license, category,
 * a long description and a screenshot gallery.
 *
 * This store's irony is all here: it is the one with the **best metadata** and at the same time the
 * only scraped one whose **download** needs a human gesture. The practical consequence is good,
 * though: the hash published here ends up in `AppVersion.sha256`, and from there the pipeline uses
 * it on the assisted path too — a file taken from a WebView is verified against a value the store
 * declared beforehand.
 *
 * ### Two labels not to be swapped
 *
 * - "**Rating**", in the info tables, is the **age classification** (`+12`), not a score. The score
 *   is in `#rating-inner-text` (`4.3`) and goes through no table. Reading the wrong row would give
 *   every app one and a half stars.
 * - "**Certificate signature**" is **MD5**, not SHA-256, despite the icon uptodown puts next to it
 *   being called `icon-40-sha256`. See [UptodownTables].
 */
internal class UptodownDetailParser(
    private val config: UptodownConfig,
    private val tables: UptodownTables,
) {

    fun parse(html: String, url: String, ref: StoreAppRef): StoreResult<UptodownDetail> =
        parseHtmlOrNotFound(html, url) { document ->
            val title = document.textOrNull(config.selectors.detailTitle) ?: return@parseHtmlOrNotFound null
            val rows = tables.infoRows(document)
            val file = tables.fileInfo(rows)

            UptodownDetail(
                listing = StoreListingDetail(
                    summary = StoreListingSummary(
                        storeId = StoreId.UPTODOWN,
                        ref = ref,
                        title = title,
                        packageName = file.packageName,
                        summary = LocalizedText.of(document.textOrNull(config.selectors.detailSummary)),
                        developer = document.textOrNull(config.selectors.detailAuthor),
                        iconUrl = document.absUrlOrNull(config.selectors.detailIcon, "src"),
                        categories = listOfNotNull(file.category),
                        latestVersionName = document.textOrNull(config.selectors.detailVersion),
                        // uptodown publishes the version code nowhere on the site: not in the
                        // listing, not in the version list, not on the download page. The
                        // `data-version-id` is **not** a version code — it is the file's
                        // identifier in their archive, and it grows over time across all apps
                        // together. Confusing them would give an anti-downgrade rule comparing
                        // numbers unrelated to the system's.
                        latestVersionCode = null,
                        rating = TextValues.rating(document.textOrNull(config.selectors.detailRating)),
                        ratingCount = ratingCountOf(document),
                        downloadsLabel = file.downloadsLabel,
                        lastUpdated = file.publishedAt,
                    ),
                    description = LocalizedText.of(document.textOrNull(config.selectors.detailDescription)),
                    screenshots = document.all(config.selectors.detailScreenshot)
                        .mapNotNull(::screenshotOf)
                        .distinct()
                        .map { Screenshot(url = it) },
                    license = file.license,
                ),
                currentFileId = document.attrOrNull(config.selectors.detailCurrentFile, FILE_ID_ATTRIBUTE)
                    ?.takeIf { it.all(Char::isDigit) },
                currentFile = file,
            )
        }

    /** `4,143 reviews` -> `4143`. The thousands separator is the English one. */
    private fun ratingCountOf(document: HtmlPage): Int? =
        document.textOrNull(config.selectors.detailRatingCount)
            ?.replace(GROUPING, "")
            ?.toIntOrNull()

    /**
     * The large image, not the thumbnail.
     *
     * uptodown puts the 150 px version in `src` and the larger one in `data-src-large`. With `src`
     * the full-screen gallery would show blurry images, and that would be a defect visible only by
     * opening a screenshot — never in a test checking "the URL is not null".
     */
    private fun screenshotOf(image: HtmlPage): String? =
        image.ownAttrOrNull(LARGE_SOURCE_ATTRIBUTE) ?: image.ownAbsUrlOrNull("src")

    private companion object {
        const val FILE_ID_ATTRIBUTE = "data-file-id"
        const val LARGE_SOURCE_ATTRIBUTE = "data-src-large"
        val GROUPING = Regex("""[,.\s]""")
    }
}
