package com.multistore.store.liteapks.parser

import com.multistore.core.model.ArtifactType
import com.multistore.core.model.VersionRef
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseFailed
import com.multistore.store.common.html.parseHtml
import com.multistore.store.liteapks.LiteapksConfig
import com.multistore.store.liteapks.LiteapksRefs
import com.multistore.store.liteapks.LiteapksSelectors
import java.util.Base64

/**
 * The two download pages: the file list, and a single file's page.
 *
 * ### The list has two markup forms, and anchoring to the container would lose one
 *
 * With more than one version the blocks sit inside a `div#dl-versions` and each has a
 * `button.dl-version-tab`; with a single version that container **does not exist** and the block is
 * a `div` with a coloured heading. Measured across 31 pages: **17 with the tabs, 14 without**. The
 * row selector is therefore `a.dl-item[href]` with no container — anchoring it to `#dl-versions`
 * would lose nearly half this store's downloads, silently.
 *
 * ### Two kinds of file, told apart by the host
 *
 * Rows pointing at `liteapks.com/download/{stem}/{n}` are the files the store has **modified**, and
 * they sit behind an intermediate page. Rows pointing elsewhere — `gp4.liteapks.com`,
 * `down.appsupload.com` — are the "Original file on Google Play" block, i.e. the **unmodified** APK,
 * served directly. They are 11 rows out of 66, and on one listing out of thirty-one
 * (`minecraft-earth`) they are the only file that exists: discarding them would mean a listing that
 * offers nothing.
 *
 * ### Where the version is written: it depends, and it has been counted
 *
 * Across 66 real rows, **22** carry the version in their own label (`v1.26.10.4 Final - Mod 1`, or
 * `3.0.20 Original` without the `v` for original files) and **44** do not — they say only the
 * variant (`Premium/Web`, `Premium`) because the version is on the heading of the block grouping
 * them (`v12.10.1 - Mod`). Neither source alone covers everything; the two together cover 66 out of
 * 66.
 */
internal class LiteapksDownloadParser(private val config: LiteapksConfig) {

    /** A file offered by the download page. */
    data class File(
        val versionName: String,
        val ref: VersionRef,
        val sizeBytes: Long?,
        val artifactType: ArtifactType,
        /** `true` for the "Original file on Google Play" block: the unmodified APK. */
        val isOriginal: Boolean,
    )

    fun parseFiles(html: String, baseUrl: String): StoreResult<List<File>> =
        parseHtml(html, baseUrl) { document ->
            document.all(config.selectors.downloadItem)
                .mapRowsOrFail(LiteapksSelectors.DOWNLOAD_ITEM_DEFAULT, ::fileOf)
        }

    /**
     * The file's URL, which the slot page carries in base64 inside an attribute.
     *
     * `<div id="download" data-link="aHR0cHM6…">`, present on 14 sampled slots out of 14. The
     * visible button starts with `href="#!"` and their JavaScript fills it by decoding **this same
     * attribute**: there is no second source and nothing to wait for.
     *
     * The decoded URL is normalised with [Urls.normalizeFileUrl], and on this store the
     * normalisation is not caution: the same CDNs serve both forms mixed —
     * `…/Auto Text/Auto Text v6.0.8 (PREMIUM).apk` with raw spaces and
     * `…/AutoResponder%20for%20Telegram/…` already encoded. Encoding twice produces `%2520` and the
     * worker answers **404 `NoSuchKey`**; not encoding produces a URL OkHttp rejects.
     */
    fun parseSlotLink(html: String, baseUrl: String): StoreResult<String> =
        parseHtml(html, baseUrl) { document ->
            val encoded = document.attr(config.selectors.slotDownload, DATA_LINK)
            val decoded = runCatching { String(Base64.getDecoder().decode(encoded)) }.getOrNull()
                ?.trim()
                ?.takeIf { Urls.isSecureOrLoopback(it) }
                ?: document.parseFailed(DATA_LINK_DECODED)
            Urls.normalizeFileUrl(decoded)
        }

    private fun fileOf(row: HtmlPage): File? {
        val url = row.ownAbsUrlOrNull(HREF) ?: return null
        val slot = LiteapksRefs.downloadStemFromUrl(url)
            ?.let { stem -> url.substringAfterLast('/').toIntOrNull()?.let { LiteapksRefs.Slot(stem, it) } }
        val label = row.textOrNull(config.selectors.downloadItemLabel)
        val version = versionOf(row, label) ?: return null

        return File(
            versionName = version,
            ref = slot?.let { LiteapksRefs.slotRef(it.stem, it.index) } ?: LiteapksRefs.directRef(url),
            // The size is rounded by the store — `800 MB` for a file that does not weigh
            // 838,860,800 bytes — so it lives in `AppVersion.sizeBytes`, which is for display, and
            // **not** in `expectedSize`, which is for verification. It is the same rule apkcombo
            // taught with its `119 MB`.
            sizeBytes = TextValues.byteSize(row.textOrNull(config.selectors.downloadItemSize)),
            // For direct files the type is told by the name; for slots the name does not exist yet
            // and the default is `APK`, which is what 55 rows out of 66 declare. The real type is
            // re-read by `getDownloadLink` from the file, when the name finally exists.
            artifactType = if (slot == null) Urls.artifactTypeOf(LiteapksRefs.fileNameOf(url)) else ArtifactType.APK,
            isOriginal = slot == null,
        )
    }

    /**
     * The row's version: its own label, or the block's heading.
     *
     * **The two sources exclude each other, and it is measured**: across 66 real rows, 22 have the
     * version on the row and a heading with a group name (`Minecraft - Official Versions`), 44 have
     * the heading with the version and the row with only the variant (`Premium/Web`). Rows with
     * **both**: zero. Rows with **neither**: zero.
     *
     * Two consequences follow, and the second corrects what this note said before fault injection:
     *
     *  - **trying both is the defence**: one alone covers 22 rows out of 66 or 44, and the others
     *    would be discarded — including the eleven of the "Original file" block, which on
     *    `minecraft-earth` are the only file that exists;
     *  - **the order, on the other hand, is not.** Since it never happens that both carry a version,
     *    swapping them changes no outcome. The row comes first only because it is the more specific
     *    of the two; the injection that swaps them stays green, and that has to be said rather than
     *    letting the opposite be assumed.
     *
     * What really bears the weight, besides trying both, is the **variant in parentheses** when the
     * version comes from the heading: without it, Telegram's two v12.10.1 files — `Premium/Web` and
     * `Premium` — would be two versions with the identical name, i.e. two indistinguishable rows in
     * the history.
     */
    private fun versionOf(row: HtmlPage, label: String?): String? {
        if (label != null && VERSION_LABEL.matches(label)) return label.removePrefix(VERSION_PREFIX)
        val header = row.closest(config.selectors.downloadGroup)
            ?.textOrNull(config.selectors.downloadGroupHeader)
            ?.takeIf { VERSION_LABEL.matches(it) }
            ?.removePrefix(VERSION_PREFIX)
            ?: return null
        return if (label.isNullOrBlank()) header else "$header ($label)"
    }

    private companion object {
        const val HREF = "href"
        const val DATA_LINK = "data-link"
        const val DATA_LINK_DECODED = "div#download[data-link] (base64 of an https URL)"
        const val VERSION_PREFIX = "v"

        /**
         * `v12.10.1 - Mod`, `v1.26.10.4 Final - Mod 1`, `3.0.20 Original`.
         *
         * The `v` is optional because the "Original file" block's rows do not have it, and without
         * that tolerance those 11 rows out of 66 would be left without a version — i.e. would be
         * discarded, and with them `minecraft-earth`'s only file.
         */
        val VERSION_LABEL = Regex("""v?\d[\d.]*([ -].*)?""")
    }
}
