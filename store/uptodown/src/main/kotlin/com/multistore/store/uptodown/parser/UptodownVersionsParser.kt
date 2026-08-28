package com.multistore.store.uptodown.parser

import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.parseHtml
import com.multistore.store.uptodown.UptodownConfig

/**
 * `/{slug}.en.uptodown.com/android/versions`: the full list of published files.
 *
 * It justifies the listing's second request for two measured reasons. The first is quantity: the
 * listing shows **6**, this page **20**, with no `?page=` and no load buttons — they are all the
 * ones uptodown keeps. The second is that the **current** version is here too, which the list at
 * the foot of the listing skips: taking it from there would have meant reconstructing it by hand
 * and giving it a `VersionRef` of a different shape, i.e. two rows for the same file the moment the
 * user also opens the full list.
 */
internal class UptodownVersionsParser(
    @Suppress("unused") private val config: UptodownConfig,
    private val tables: UptodownTables,
) {

    fun parse(html: String, url: String): StoreResult<List<UptodownVersionEntry>> =
        parseHtml(html, url) { document -> tables.versionEntries(document) }
}
