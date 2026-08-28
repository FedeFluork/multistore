package com.multistore.store.uptodown.parser

import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.parseHtmlOrNotFound
import com.multistore.store.uptodown.UptodownConfig

/** The page offering a file, with what is needed to verify it after the tap. */
internal data class UptodownDownload(
    /** The file this page will serve: the button's `data-file-id`. */
    val fileId: String?,
    val info: UptodownFileInfo,
    /** `true` if the page mounts the Turnstile widget, i.e. if the human gesture is really needed. */
    val gatedByChallenge: Boolean,
)

/**
 * uptodown's download page, which does **not** contain a link to the file.
 *
 * The button is a `<button>`, not an anchor. On click the page runs a Cloudflare Turnstile and
 * posts the token to `POST /ajax/app/{appID}/file/{fileID}/download-url`, which answers with the
 * path to append to `https://dw.uptodown.com/dwn/`. **We do not do that**: calling that endpoint
 * with a token we did not obtain by running the challenge would be pretending to have solved it,
 * and that is exactly where this project separates what is permitted from what is not.
 *
 * What is read here is therefore all **metadata**, and it justifies the request for one reason
 * only: the "SHA256" row. Thanks to it, a file the user downloads with a tap inside the WebView
 * reaches the verification pipeline with an expected value — that is, it gets verified just as much
 * as a direct download. Without it, the assisted path would be the only one with no comparison.
 */
internal class UptodownDownloadParser(
    private val config: UptodownConfig,
    private val tables: UptodownTables,
) {

    fun parse(html: String, url: String): StoreResult<UptodownDownload> =
        parseHtmlOrNotFound(html, url) { document ->
            val button = document.oneOrNull(config.selectors.downloadButton)
                ?: return@parseHtmlOrNotFound null
            UptodownDownload(
                fileId = button.ownAttrOrNull(FILE_ID_ATTRIBUTE)?.takeIf { it.all(Char::isDigit) },
                info = tables.fileInfo(tables.infoRows(document)),
                gatedByChallenge = document.has(config.selectors.downloadTurnstile),
            )
        }

    private companion object {
        const val FILE_ID_ATTRIBUTE = "data-file-id"
    }
}
