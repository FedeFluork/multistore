package com.multistore.store.apkmirror.parser

import com.multistore.core.model.Sha256
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.parseHtmlOrNotFound

/**
 * What a single variant's page adds to the release table.
 *
 * [fileSha256] is the **file's** hash, [signerSha256] the **certificate's**: two different things
 * apkmirror publishes in the same panel, and confusing them would mean comparing an APK against
 * the fingerprint of the key that signed it.
 */
internal data class ApkMirrorVariantDetail(
    val versionName: String?,
    val versionCode: Long?,
    val packageName: String?,
    val sizeBytes: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val fileSha256: Sha256?,
    val signerSha256: Sha256?,
    val downloadUrl: String?,
)

/**
 * A variant's page: where apkmirror becomes the store with the best data after F-Droid.
 *
 * For the same file it publishes: `packageName`, `versionCode`, size **to the byte**, `minSdk`,
 * `targetSdk`, the **file's SHA-256** and the **signing certificate's SHA-256** with its DN. Four
 * of the seven pre-install steps served by one page.
 *
 * ### The difference between APK and bundle, and why it matters
 *
 * The download panel has **two** distinct sections:
 *
 * - certificate fingerprints — always present, bundles included: SHA-1 and SHA-256 of the
 *   certificate, plus the subject.
 * - file hashes — present **only on single APKs**: MD5, SHA-1, SHA-256 of the file.
 *
 * On a bundle the second is missing, and rightly: there is no single file to hash. Hence
 * `providesHash = SOMETIMES` instead of `ALWAYS`, which would be a false declaration for a quarter
 * of the artifacts.
 *
 * ### Why the two hashes are found by label and not by position
 *
 * Both sections contain a `SHA-256:` followed by 64 hex characters. Taking "the page's first
 * SHA-256" would give, on a single APK, the **certificate's** fingerprint in place of the file's —
 * and pre-install verification would compare the download's hash against a key's fingerprint,
 * failing always and for the wrong reason.
 */
internal class ApkMirrorVariantParser(private val config: ApkMirrorConfig) {

    fun parse(html: String, url: String): StoreResult<ApkMirrorVariantDetail> =
        parseHtmlOrNotFound(html, url) { document ->
            val specs = document.all(config.selectors.variantSpec).mapNotNull { it.flatText() }
            if (specs.isEmpty()) return@parseHtmlOrNotFound null

            val identity = specs.firstOrNull { config.selectors.labelPackage in it }
            val sdkLine = specs.firstOrNull { config.selectors.labelMinSdk in it }
            val modal = document.oneOrNull(config.selectors.variantSafeModal)?.flatText()

            ApkMirrorVariantDetail(
                versionName = versionName(identity),
                versionCode = TextValues.parenthesizedCode(identity),
                packageName = labelled(identity, config.selectors.labelPackage)
                    ?.substringBefore(' ')
                    ?.takeIf { it.contains('.') },
                sizeBytes = specs.firstNotNullOfOrNull(TextValues::byteSize),
                minSdk = TextValues.apiLevel(labelled(sdkLine, config.selectors.labelMinSdk)),
                targetSdk = TextValues.apiLevel(labelled(sdkLine, config.selectors.labelTargetSdk)),
                fileSha256 = sha256After(modal, config.selectors.labelFileHashes),
                signerSha256 = sha256After(modal, config.selectors.labelCertificateHashes),
                downloadUrl = document
                    .absUrlOrNull(config.selectors.variantDownloadButton, "href"),
            )
        }

    /** `App: Firefox Version: 154.0 (2016178287) … Package: org.mozilla.firefox` -> `154.0`. */
    private fun versionName(identity: String?): String? =
        labelled(identity, config.selectors.labelVersion)
            ?.substringBefore('(')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** The text following a label, up to the next one or the end of the line. */
    private fun labelled(text: String?, label: String): String? {
        if (text == null) return null
        val start = text.indexOf(label)
        if (start < 0) return null
        return text.substring(start + label.length).trim().takeIf { it.isNotBlank() }
    }

    /**
     * The first SHA-256 appearing **after** a given heading.
     *
     * If the heading is absent — the bundle case for file hashes — it returns `null`, which is the
     * correct way of saying "this artifact has no published hash".
     */
    private fun sha256After(text: String?, heading: String): Sha256? {
        if (text == null) return null
        val start = text.indexOf(heading)
        if (start < 0) return null
        val tail = text.substring(start + heading.length)
        // It stops at the next section: without that, on a bundle the search for the file-hash
        // block would slide forward to a hexadecimal from another part of the page.
        val until = tail.indexOf(SECTION_BREAK).takeIf { it > 0 } ?: tail.length
        return TextValues.hex(tail.substring(0, until), SHA256_CHARS)?.let(Sha256::parseOrNull)
    }

    /**
     * The node's text with whitespace normalised.
     *
     * apkmirror indents its HTML: a label followed by twenty spaces and the next label becomes a
     * string with twenty spaces in the middle, and searching inside it works while slicing what
     * follows does not.
     */
    private fun HtmlPage.flatText(): String? =
        textOrNull()?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        const val SHA256_CHARS = 64
        const val SECTION_BREAK = "APK "
        val WHITESPACE = Regex("""\s+""")
    }
}
