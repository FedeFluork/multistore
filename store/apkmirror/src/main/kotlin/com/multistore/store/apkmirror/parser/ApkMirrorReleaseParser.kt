package com.multistore.store.apkmirror.parser

import com.multistore.core.model.ArtifactType
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.apkmirror.ApkMirrorRefs
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.parseHtml
import kotlin.time.Instant

/** A variant listed in a release's table: one file, with its ABI and its dpi. */
internal data class ApkMirrorVariant(
    val path: String,
    val versionName: String,
    val versionCode: Long?,
    val artifactType: ArtifactType,
    val abis: List<String>,
    val minSdk: Int?,
    val dpi: String?,
    val publishedAt: Instant?,
)

/**
 * The variants table of a release.
 *
 * apkmirror's most profitable page: one request gives, for every variant, **name, `versionCode`,
 * type, ABI, `minSdk` and date**. Only the hash is missing, and that lives on the individual
 * variant's page.
 *
 * The version code deserves a line: it sits in an unlabelled span right under the link. It is not
 * obvious looking at the page in a browser — the site renders it in light grey as though it were a
 * detail — and it is the number the whole anti-downgrade rule rests on.
 *
 * **The version code belongs to the variant, not to the release**, and the first draft of this
 * comment said the opposite. The mistake came from a comparison made on two pages only, and the
 * full fixture disproved it: nine variants of one release carry **three** distinct codes.
 *
 * | Variants | `versionCode` |
 * |---|---|
 * | `154-0`, `154-0-4`, `154-0-5`, `154-0-6` | 2016178287 |
 * | `154-0-2` | 2016178690 |
 * | `154-0-3`, `154-0-7`, `154-0-8`, `154-0-9` | 2016178695 |
 *
 * It is the publisher's convention, encoding the ABI inside the number. The practical consequence:
 * propagating one variant's code to the others — which is what an adapter written from that
 * comment would have done — would give half the artifacts a version code that is not theirs.
 */
internal class ApkMirrorReleaseParser(private val config: ApkMirrorConfig) {

    fun parse(html: String, url: String, releasePath: String): StoreResult<List<ApkMirrorVariant>> =
        parseHtml(html, url) { document ->
            val table = document.oneOrNull(config.selectors.releaseVariantsTable)
                ?: return@parseHtml emptyList()
            table.all(config.selectors.releaseRow).mapNotNull { row -> variantOf(row, releasePath) }
        }

    private fun variantOf(row: HtmlPage, releasePath: String): ApkMirrorVariant? {
        val link = row.oneOrNull(config.selectors.releaseVariantLink) ?: return null
        val href = link.ownAbsUrlOrNull("href") ?: return null
        val path = ApkMirrorRefs.contentPath(href) ?: return null
        // The table's first row is the header and has no link; rows from other releases do not
        // occur, but the prefix check costs nothing and closes the case of a sidebar widget
        // ending up inside the table.
        if (!path.startsWith("$releasePath/")) return null

        val cells = row.all(config.selectors.releaseCell)
        val badges = row.all(config.selectors.releaseBadge).mapNotNull { it.ownTextOrNull() }

        return ApkMirrorVariant(
            path = path,
            versionName = link.ownTextOrNull() ?: return null,
            versionCode = versionCodeOf(cells.firstOrNull()),
            artifactType = if (badges.any { it.equals(config.selectors.badgeBundle, ignoreCase = true) }) {
                // An apkmirror "APK bundle" is base plus splits, not installable by
                // `PackageInstaller` as it is. Marking it for what it is makes version selection
                // discard it until we know how to open it.
                ArtifactType.APKM
            } else {
                ArtifactType.APK
            },
            abis = TextValues.abis(cells.getOrNull(ARCH_CELL)?.ownTextOrNull()),
            minSdk = TextValues.apiLevel(cells.getOrNull(MIN_VERSION_CELL)?.ownTextOrNull()),
            dpi = cells.getOrNull(DPI_CELL)?.ownTextOrNull(),
            publishedAt = TextValues.utcDateTime(
                row.attrOrNull(config.selectors.searchDate, "data-utcdate"),
            ),
        )
    }

    /**
     * The version code, which in the first cell is a bare number with no label.
     *
     * In the same cell, with the **same class**, sits the date. Taking the first would work until
     * apkmirror swapped the two, and then the version code would become the year — silently, and
     * with the effect of proposing a downgrade on every update. Only a span whose text is
     * **entirely** digits is therefore accepted.
     */
    private fun versionCodeOf(cell: HtmlPage?): Long? = cell
        ?.all(config.selectors.releaseVersionCode)
        ?.mapNotNull { it.ownTextOrNull() }
        ?.firstNotNullOfOrNull { text -> text.takeIf(ALL_DIGITS::matches)?.toLongOrNull() }

    private companion object {
        const val ARCH_CELL = 1
        const val MIN_VERSION_CELL = 2
        const val DPI_CELL = 3
        val ALL_DIGITS = Regex("""\d{3,}""")
    }
}
