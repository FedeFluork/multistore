package com.multistore.store.an1.parser

import com.multistore.core.model.ArtifactType
import com.multistore.store.an1.An1Config
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.StoreErrors
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseHtml

/**
 * The download page, i.e. the second and last hop of the download.
 *
 * ### Two `.apk` files on the same page, on the same host
 *
 * The app's file sits in an anchor the theme keeps hidden until a timer reveals it. Next to it, in
 * another anchor and on the **same host**, sits an1's own store app — a real `.apk`. Only the
 * anchor's id tells the two apart, which is why the host filter is the **second** check and not the
 * first: it guards against the sponsors, which live elsewhere.
 *
 * The countdown is not a gate: the `href` is already in the HTML of the **first** response. No
 * token, no session, no signature earned by waiting.
 */
internal class An1DownloadParser(private val config: An1Config) {

    /** The file to download: absolute URL, name and type. */
    data class File(val url: String, val fileName: String, val artifactType: ArtifactType)

    fun parse(html: String, url: String): StoreResult<File> {
        val href = parseHtml(html, url) { it.absUrl(config.selectors.downloadLink, HREF) }
        val target = when (href) {
            is StoreResult.Success -> href.value
            is StoreResult.Failure -> return href
            StoreResult.Unsupported -> return StoreResult.Unsupported
        }

        if (!hostMatches(target)) {
            // **`NotFound`, not a parse failure.** The anchor is there and was found: the markup
            // has not changed. The link simply points outside an1's hosts — on two listings out of
            // twelve it is a shortener ending at Google Drive.
            //
            // It is not followed, and that is a choice: an APK from an arbitrary host, on a store
            // publishing neither a package name nor (for those files) a hash, is the case where the
            // verification pipeline has nothing left to say no with. The host list is the last
            // structural control remaining.
            //
            // Diagnosing it as changed markup would send people looking for a selector that is
            // perfectly fine.
            return StoreResult.Failure(StoreError.NotFound)
        }
        if (!Urls.isSecureOrLoopback(target)) {
            return StoreResult.Failure(
                StoreErrors.parseFailure("${config.selectors.downloadLink} (https)", target),
            )
        }

        val fileName = Urls.fileNameOf(target, FALLBACK_NAME)
        return StoreResult.Success(
            File(
                url = target,
                fileName = fileName,
                artifactType = Urls.artifactTypeOf(fileName),
            ),
        )
    }

    /**
     * The host, compared whole and not with `contains`.
     *
     * `contains("files.an1.net")` would also say yes to `files.an1.net.some-hostile.tld`, which is
     * a completely different host. The value to compare is the URL's authority.
     *
     * Loopback passes because the test double serves the fake CDN on `127.0.0.1`.
     */
    private fun hostMatches(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return config.downloadHosts.any { it.equals(host, ignoreCase = true) }
    }

    private companion object {
        const val HREF = "href"
        const val FALLBACK_NAME = "an1.apk"
    }
}
