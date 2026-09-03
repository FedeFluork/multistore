package com.multistore.store.apkcombo.parser

import com.multistore.core.model.ArtifactType
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseHtml
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

/** A downloadable variant, as apkcombo lists it on the download page. */
internal data class ApkComboVariant(
    val url: String,
    /**
     * The object's name inside the signed URL.
     *
     * It is the variant's identity, and it is needed because the version code **is not**: three
     * variants of the same app can share one. See `ApkComboRefs.versionRef`.
     */
    val objectKey: String,
    val versionName: String,
    val versionCode: Long?,
    val artifactType: ArtifactType,
    val sizeBytes: Long?,
    val minSdk: Int?,
    val abis: List<String>,
    val fileName: String,
    val expiresAt: Instant?,
    /** `true` for the variant apkcombo puts in the "Download" panel, i.e. the recommended one. */
    val recommended: Boolean,
)

/**
 * apkcombo's download page, which is also its real list of versions.
 *
 * The site's old-versions page lists more releases but publishes **only** name and date: no version
 * code, no size, no ABI. This page instead carries everything, for each of the up-to-eight variants
 * of the release — and is therefore the only one version selection can really work on.
 *
 * ### The file's URL is already here, and asking for the redirect is unnecessary
 *
 * The `href` wraps a percent-encoded signed URL. Following it would work, but the real URL is
 * already inside the query: decoding it saves a hop **on their servers**, not on ours. It also
 * carries two things that would otherwise be lost: the file name the store chose, and the
 * signature's **exact expiry**, which the download resolution exists to carry — a signed URL
 * cached and reused later becomes an opaque 403.
 */
internal class ApkComboDownloadParser(private val config: ApkComboConfig) {

    fun parse(html: String, url: String, appTitle: String?): StoreResult<List<ApkComboVariant>> =
        parseHtml(html, url) { document ->
            val recommended = variantsIn(document, config.selectors.downloadBestTab, appTitle, true)
            val all = variantsIn(document, config.selectors.downloadVariantsTab, appTitle, false)
            // The page's two panels show the same set, with the recommended one repeated at the
            // top. One entry per **object key** is kept, preferring the marked one.
            //
            // Not per URL, and that distinction cost a red canary on 03/09/2026. Each anchor wraps
            // its **own** signature, and the two panels are signed a moment apart: on Spotify the
            // recommended `.apks` appeared twice with URLs differing in one character —
            // `X-Amz-Expires=14399` against `14400`. Deduplicating on the URL therefore kept both,
            // `getAppDetails` published two versions with the **same** `VersionRef` (which is
            // derived from the object key, not the URL), and the invariant the canary guards — as
            // many distinct refs as variants — broke.
            //
            // It is also the worst shape of intermittence: whether the two signatures land on the
            // same second decides it, so the same page is fine most of the time. `objectKey` is
            // documented on `ApkComboVariant` as the variant's identity, and a signed URL is
            // precisely what is *not* one.
            (recommended + all).distinctBy { it.objectKey }
        }

    private fun variantsIn(
        document: HtmlPage,
        tabSelector: String,
        appTitle: String?,
        recommended: Boolean,
    ): List<ApkComboVariant> {
        val tab = document.oneOrNull(tabSelector) ?: return emptyList()
        return tab.all(config.selectors.downloadArchGroup).flatMap { group ->
            val abis = TextValues.abis(group.textOrNull(config.selectors.downloadArchLabel))
            group.all(config.selectors.downloadVariant).mapNotNull { variant ->
                variantOf(variant, abis, appTitle, recommended)
            }
        }
    }

    private fun variantOf(
        variant: HtmlPage,
        abis: List<String>,
        appTitle: String?,
        recommended: Boolean,
    ): ApkComboVariant? {
        val href = variant.ownAttrOrNull("href") ?: return null
        val signed = Urls.queryParam(href, REDIRECT_PARAM)
            ?: variant.ownAbsUrlOrNull("href")
            ?: return null
        if (!Urls.isHttps(signed)) return null

        val label = variant.textOrNull(config.selectors.downloadVariantName) ?: return null
        val specs = variant.all(config.selectors.downloadVariantSpec).mapNotNull { it.ownTextOrNull() }

        return ApkComboVariant(
            url = signed,
            objectKey = objectKeyOf(signed) ?: return null,
            versionName = versionNameOf(label, appTitle) ?: return null,
            versionCode = TextValues.parenthesizedCode(
                variant.textOrNull(config.selectors.downloadVariantCode),
            ),
            artifactType = when {
                variant.has(config.selectors.downloadVariantTypeXapk) -> ArtifactType.XAPK
                else -> ArtifactType.APK
            },
            sizeBytes = specs.firstNotNullOfOrNull(TextValues::byteSize),
            minSdk = specs.firstNotNullOfOrNull(TextValues::apiLevel),
            abis = abis,
            fileName = fileNameOf(signed),
            expiresAt = presignedExpiry(signed),
            recommended = recommended,
        )
    }

    /**
     * `Telegram 12.10.0` -> `12.10.0`.
     *
     * With the title known it is removed; without, the last block is taken. The titleless case
     * exists because the download page can be opened without having read the listing first — and it
     * is less precise, not wrong: a version name containing the app's name would appear that way in
     * "My apps".
     */
    private fun versionNameOf(label: String, appTitle: String?): String? {
        val stripped = appTitle
            ?.let { label.removePrefix(it) }
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != label }
        return stripped ?: label.substringAfterLast(' ').trim().takeIf { it.isNotBlank() }
    }

    /** The last path segment of the signed URL, query stripped: it identifies the file. */
    private fun objectKeyOf(signedUrl: String): String? = runCatching {
        java.net.URI(signedUrl).path.orEmpty().substringAfterLast('/')
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The name the store chose, from the content disposition signed inside the URL. */
    private fun fileNameOf(signedUrl: String): String {
        val disposition = Urls.queryParam(signedUrl, DISPOSITION_PARAM)
        val declared = disposition?.let { FILE_NAME.find(it)?.groupValues?.get(1) }
        return declared?.let { Urls.fileNameOf("/$it", FALLBACK_FILE_NAME) }
            ?: Urls.fileNameOf(signedUrl, FALLBACK_FILE_NAME)
    }

    /**
     * When the signature expires: the signing date plus the expiry parameter.
     *
     * Measured: apkcombo signs for **14,400 seconds**, four hours. Whoever caches the resolution
     * has to know, and it is the only way to tell "the store blocked us" from "the URL was old".
     */
    private fun presignedExpiry(signedUrl: String): Instant? {
        val issued = Urls.queryParam(signedUrl, AMZ_DATE) ?: return null
        val seconds = Urls.queryParam(signedUrl, AMZ_EXPIRES)?.toLongOrNull() ?: return null
        val start = runCatching {
            LocalDateTime.parse(issued, AMZ_DATE_FORMAT).toInstant(ZoneOffset.UTC)
        }.getOrNull() ?: return null
        return Instant.fromEpochMilliseconds(start.toEpochMilli() + seconds * MILLIS_PER_SECOND)
    }

    private companion object {
        const val REDIRECT_PARAM = "u"
        const val DISPOSITION_PARAM = "response-content-disposition"
        const val AMZ_DATE = "X-Amz-Date"
        const val AMZ_EXPIRES = "X-Amz-Expires"
        const val FALLBACK_FILE_NAME = "apkcombo.apk"
        const val MILLIS_PER_SECOND = 1000L
        val FILE_NAME = Regex("""filename="?([^"';]+)"?""")
        val AMZ_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
    }
}
