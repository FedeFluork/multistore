package com.multistore.store.apkmody.parser

import com.multistore.core.model.ArtifactType
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmody.ApkModyConfig
import com.multistore.store.apkmody.ApkModyRefs
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseHtmlOrNotFound

/** The file apkmody serves for a version, with what its name declares. */
internal data class ApkModyFile(
    val url: String,
    val fileName: String,
    /** From the CDN path, which contains the package name. */
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val artifactType: ArtifactType,
)

/**
 * The link to the file, from any of the three pages publishing it.
 *
 * apkmody serves the same file from the download page (where it sits in a list), from the history
 * page (where it sits in the button) and from a per-version history page (likewise, for the chosen
 * version). The difference between the three is the selector, and there is no reason for three
 * parsers: what really changes is **which link to take**, and that is the same question on all
 * three.
 *
 * ### The host filter is not caution: it is the only thing separating the file from the advert
 *
 * The download list contains **two** anchors, and the second points at apkmody's own installer. It
 * is an `.apk`, it sits next to the real file, it has the same markup and at the top it carries the
 * icon of the app being downloaded. A parser taking "the list's first `.apk`" would sometimes
 * install the wrong app — and the package block would stop it *after* the download, which is late.
 * The discriminator is the host, in [ApkModyConfig.downloadHost].
 *
 * The same anchors appear a second time inside **HTML comments**, where Jsoup does not see them:
 * comments are not selectable nodes. It is the one point in this file where doing nothing is the
 * right thing.
 */
internal class ApkModyDownloadParser(private val config: ApkModyConfig) {

    fun parse(html: String, url: String): StoreResult<ApkModyFile> =
        parseHtmlOrNotFound(html, url) { document -> fileIn(document) }

    /** The file, if this page publishes one. Also used by whoever reads the history. */
    fun fileIn(document: HtmlPage): ApkModyFile? {
        val candidates = document.all(config.selectors.historyLatestLink) +
            document.all(config.selectors.downloadItem)
        return candidates.firstNotNullOfOrNull(::fileOf)
    }

    private fun fileOf(anchor: HtmlPage): ApkModyFile? {
        val href = anchor.ownAbsUrlOrNull("href") ?: return null
        if (!Urls.isHttps(href)) return null
        if (hostOf(href) != config.downloadHost) return null

        val fileName = Urls.fileNameOf(href, FALLBACK_FILE_NAME)
        return ApkModyFile(
            url = href,
            fileName = fileName,
            packageName = ApkModyRefs.packageNameFromDownloadUrl(href),
            versionName = ApkModyRefs.versionNameFromFileName(fileName),
            versionCode = ApkModyRefs.versionCodeFromFileName(fileName),
            artifactType = artifactTypeOf(fileName),
        )
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()

    /**
     * The type from the name's suffix.
     *
     * On the fixtures and on every observed page apkmody serves `.apk`, but its own installation
     * guide names split-container formats: the type is read from the file rather than assumed, so
     * the day a container arrives it is not handed to `PackageInstaller` as though it were an APK.
     */
    private fun artifactTypeOf(fileName: String): ArtifactType =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "xapk" -> ArtifactType.XAPK
            "apkm" -> ArtifactType.APKM
            "apks" -> ArtifactType.APKS
            else -> ArtifactType.APK
        }

    private companion object {
        const val FALLBACK_FILE_NAME = "apkmody.apk"
    }
}
