package com.multistore.store.apkcombo.parser

import com.multistore.core.model.ArtifactType
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseFailed
import com.multistore.store.common.html.parseHtml
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
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
            val variants = (recommended + all).distinctBy { it.objectKey }

            // Anchors on the page and **not one** of them readable is a parse failure, not an app
            // with nothing to download. It is the `mapRowsOrFail` rule of `:store:common` applied
            // where this parser had been conflating the two, and it is the guard that was missing:
            // when apkcombo began wrapping URLs in a second form, every one of these anchors
            // stopped being read and the store answered "no installable package" — with no selector
            // named, nothing in diagnostics, and a nightly canary that stayed green on this check.
            //
            // **Zero** anchors stays an empty list, deliberately: that is a page offering no file,
            // which `getAppDetails` answers by falling back to the version list on the same page.
            if (variants.isEmpty() && document.has(config.selectors.downloadVariant)) {
                document.parseFailed(config.selectors.downloadVariant)
            }
            variants
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
        val signed = targetOf(variant, href) ?: return null
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

    /**
     * The real file URL, out of whichever of the **two** wrappers apkcombo used.
     *
     * Both hide it in a `u=` query parameter and there the resemblance ends, which is the whole
     * point of this function:
     *
     * - `/r2?u=<percent-encoded>` points at Cloudflare R2, and is the form this adapter was
     *   written against;
     * - `/d?u=<base64>` points at `download.pureapk.com`, and is the one that was silently costing
     *   the store half its catalogue.
     *
     * ### Why it is worth spelling out
     *
     * The second form is **not new**: the page committed as `download-no-variants.html.gz` on
     * 29/08/2026 already carried one. Reading only the first, the parser dropped its single
     * anchor, `getAppDetails` fell back to the version list, and the conclusion drawn — written
     * into that fixture's README and into CLAUDE.md — was that `com.iMe.android` publishes no
     * installable artifact and that on this store "the files live only under the per-version
     * segments". None of that was true. The page offered a 159 MB XAPK all along; what could not
     * read it was this line.
     *
     * Measured 18/09/2026 over 22 apps taken from the store's own feed: **11 serve `/d?`, 11 serve
     * `/r2?`, none both.** Half of apkcombo answered `StoreError.NotFound` to every Install, which
     * is the shape the user reported and the reason this is a one-line cause with a long comment.
     *
     * The raw parameter is read **before** percent-decoding, and that is not fastidiousness:
     * apkcombo's base64 is the standard alphabet — `/` and `=` travel unescaped, measured on five
     * anchors — so `+` is a legal character in it, and `URLDecoder` turns `+` into a space. Reading
     * the decoded value would corrupt exactly those payloads that happen to contain one.
     */
    private fun targetOf(variant: HtmlPage, href: String): String? {
        val raw = rawRedirectParam(href) ?: return variant.ownAbsUrlOrNull("href")
        return percentDecodedUrl(raw) ?: base64DecodedUrl(raw)
    }

    /** The `u=` parameter exactly as written, with no decoding applied. */
    private fun rawRedirectParam(href: String): String? {
        val query = runCatching { URI(href).rawQuery }.getOrNull() ?: return null
        for (pair in query.split('&')) {
            val separator = pair.indexOf('=')
            if (separator <= 0 || pair.substring(0, separator) != REDIRECT_PARAM) continue
            return pair.substring(separator + 1).takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun percentDecodedUrl(raw: String): String? =
        runCatching { URLDecoder.decode(raw, Charsets.UTF_8) }.getOrNull()?.takeIf(Urls::isHttps)

    private fun base64DecodedUrl(raw: String): String? =
        base64Text(raw)?.takeIf(Urls::isHttps)

    private fun base64Text(raw: String): String? = runCatching {
        String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The last path segment of the signed URL, query stripped: it identifies the file. */
    private fun objectKeyOf(signedUrl: String): String? = runCatching {
        URI(signedUrl).path.orEmpty().substringAfterLast('/')
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * The name the store chose, from whichever of the two CDNs signed the URL.
     *
     * R2 signs a `response-content-disposition`; pureapk carries `_fn`, which is base64 of a
     * percent-encoded name. Without the second the fallback would take the last path segment, and
     * on pureapk that segment **is itself base64** — the file would reach the user called
     * `Y29tLm5lcm8uaW1hZ2VfdXBzY2FsZXJfNDgwX2U1ZTc1MmVi`, with no extension. The percent-encoded
     * form is handed on as it is, because [Urls.fileNameOf] decodes once and sanitises; decoding
     * here as well would eat a literal `%` in somebody's app name.
     */
    private fun fileNameOf(signedUrl: String): String {
        val disposition = Urls.queryParam(signedUrl, DISPOSITION_PARAM)
        val declared = disposition?.let { FILE_NAME.find(it)?.groupValues?.get(1) }
            ?: Urls.queryParam(signedUrl, NAME_PARAM)?.let(::base64Text)
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
        /** pureapk's equivalent of a content disposition: base64 of a percent-encoded file name. */
        const val NAME_PARAM = "_fn"
        const val AMZ_DATE = "X-Amz-Date"
        const val AMZ_EXPIRES = "X-Amz-Expires"
        const val FALLBACK_FILE_NAME = "apkcombo.apk"
        const val MILLIS_PER_SECOND = 1000L
        val FILE_NAME = Regex("""filename="?([^"';]+)"?""")
        val AMZ_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
    }
}
