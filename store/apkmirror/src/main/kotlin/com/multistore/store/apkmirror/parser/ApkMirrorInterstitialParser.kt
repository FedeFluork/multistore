package com.multistore.store.apkmirror.parser

import com.multistore.store.api.StoreResult
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.common.html.parseHtmlOrNotFound

/**
 * The last hop before the file: the "Your download is starting…" page.
 *
 * It carries one useful datum, the download link, pointing at an endpoint with an id and a key.
 * That key **is not the same** one in the interstitial's own URL: apkmirror generates a different
 * one for the last step, so the page really has to be opened and the final URL cannot be composed
 * by hand.
 *
 * That endpoint answers **302** towards a signed R2 URL valid for an hour. The downloader follows
 * the redirect: which is why this store's network profile has to apply to the download engine too.
 */
internal class ApkMirrorInterstitialParser(private val config: ApkMirrorConfig) {

    fun parse(html: String, url: String): StoreResult<String> =
        parseHtmlOrNotFound(html, url) { document ->
            document.absUrlOrNull(config.selectors.interstitialLink, "href")
        }
}
