package com.multistore.store.uptodown.parser

import com.multistore.core.model.ArtifactType
import com.multistore.core.model.Sha256
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.uptodown.UptodownConfig
import kotlin.time.Instant

/** What the info tables say about the **file** that page offers. */
internal data class UptodownFileInfo(
    val packageName: String?,
    val sha256: Sha256?,
    val sizeBytes: Long?,
    val publishedAt: Instant?,
    val artifactType: ArtifactType,
    val abis: List<String>,
    val downloadsLabel: String?,
    val category: String?,
    val license: String?,
)

/** A row of the version list: the file, not the release. */
internal data class UptodownVersionEntry(
    val versionId: String,
    val versionName: String,
    val artifactType: ArtifactType,
    val minSdk: Int?,
    val publishedAt: Instant?,
)

/**
 * The two structures uptodown repeats identically across several pages: the info tables and the
 * version list.
 *
 * They live here and not in each parser because they are **the same**: the listing, the download
 * page and an older version's page publish the same `info-block`s, and the version list appears
 * both at the foot of the listing and on `/versions`. Three copies of the same read would be three
 * places to update a selector, and two to forget.
 */
internal class UptodownTables(private val config: UptodownConfig) {

    /** The info tables as a label -> value map. */
    fun infoRows(document: HtmlPage): Map<String, String> =
        document.all(config.selectors.infoRow).mapNotNull { row ->
            val name = row.textOrNull(config.selectors.infoName) ?: return@mapNotNull null
            val value = row.textOrNull(config.selectors.infoValue) ?: return@mapNotNull null
            name to value
        }.toMap()

    fun fileInfo(rows: Map<String, String>): UptodownFileInfo = UptodownFileInfo(
        packageName = rows[config.selectors.infoRowPackageName]?.takeIf { it.contains('.') },
        // **This is the page's only usable hash, and not the only one resembling one.** A few rows
        // above sits "Certificate signature", which uptodown accompanies with an icon called
        // `icon-40-sha256` and which is instead **MD5**: 32 hex characters,
        // `26babc62540ef0c20bfc6bacf3d3b1f5`. It does not end up in `signerSha256` and cannot,
        // because `Sha256.parseOrNull` measures the length and returns `null` — the type has
        // already done the work the label and the icon invited us to get wrong.
        sha256 = Sha256.parseOrNull(rows[config.selectors.infoRowSha256]),
        sizeBytes = TextValues.byteSize(rows[config.selectors.infoRowSize]),
        publishedAt = TextValues.monthDayYear(rows[config.selectors.infoRowDate]),
        artifactType = artifactTypeOf(rows[config.selectors.infoRowFileType]),
        abis = TextValues.abis(rows[config.selectors.infoRowArchitecture]),
        downloadsLabel = TextValues.downloadsLabel(rows[config.selectors.infoRowDownloads]),
        category = rows[config.selectors.infoRowCategory],
        license = rows[config.selectors.infoRowLicense],
    )

    fun versionEntries(document: HtmlPage): List<UptodownVersionEntry> =
        document.all(config.selectors.versionItem).mapNotNull { item ->
            val id = item.ownAttrOrNull(VERSION_ID_ATTRIBUTE)?.takeIf { it.all(Char::isDigit) }
                ?: return@mapNotNull null
            val name = item.textOrNull(config.selectors.versionName) ?: return@mapNotNull null
            UptodownVersionEntry(
                versionId = id,
                versionName = name,
                artifactType = artifactTypeOf(item.textOrNull(config.selectors.versionType)),
                // `Android + 5.0`, with the sign **before** the number. It is the only source of
                // `minSdk` uptodown publishes, and without it `VersionSelection` would consider any
                // version compatible with any device.
                minSdk = TextValues.apiLevel(item.textOrNull(config.selectors.versionSdk)),
                publishedAt = TextValues.monthDayYear(item.textOrNull(config.selectors.versionDate)),
            )
        }

    private fun artifactTypeOf(raw: String?): ArtifactType =
        when (raw?.trim()?.lowercase()) {
            "xapk" -> ArtifactType.XAPK
            "apkm" -> ArtifactType.APKM
            "apks" -> ArtifactType.APKS
            else -> ArtifactType.APK
        }

    private companion object {
        const val VERSION_ID_ATTRIBUTE = "data-version-id"
    }
}
