package com.multistore.store.apkcombo.parser

import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.apkcombo.ApkComboRefs
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseHtml

/**
 * The list of older releases.
 *
 * apkcombo's poorest page: name, type, date and minimum SDK, and nothing else. **No version code,
 * no size, no ABI** — i.e. none of the three fields version selection decides on. Returning them
 * anyway makes sense because each carries its own version ref: resolving one's download opens its
 * variants page, which does publish everything.
 *
 * The consequence to keep in mind is that a version taken from here **is not comparable** with the
 * installed one until it is resolved. A null version code is the explicit form of that fact —
 * better than inventing one from the name, which would bear no relation to the real one.
 */
internal class ApkComboVersionsParser(private val config: ApkComboConfig) {

    fun parse(html: String, url: String): StoreResult<List<AppVersion>> =
        parseHtml(html, url) { document ->
            document.all(config.selectors.oldVersionItem).mapNotNull { item ->
                val href = item.ownAttrOrNull("href") ?: return@mapNotNull null
                val segment = Urls.segments(href).lastOrNull() ?: return@mapNotNull null
                val label = item.textOrNull(config.selectors.oldVersionName) ?: return@mapNotNull null
                val description = item.textOrNull(config.selectors.oldVersionDescription)

                AppVersion(
                    versionName = label.substringAfterLast(' ').trim().ifBlank { return@mapNotNull null },
                    versionCode = null,
                    ref = ApkComboRefs.versionRef(segment, objectKey = null),
                    artifactType = if (item.has(config.selectors.downloadVariantTypeXapk)) {
                        ArtifactType.XAPK
                    } else {
                        ArtifactType.APK
                    },
                    minSdk = TextValues.apiLevel(description),
                    publishedAt = TextValues.monthDayYear(description?.substringBefore(SEPARATOR)?.trim()),
                )
            }
        }

    private companion object {
        const val SEPARATOR = "·"
    }
}
