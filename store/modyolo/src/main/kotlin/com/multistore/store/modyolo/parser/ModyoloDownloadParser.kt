package com.multistore.store.modyolo.parser

import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.store.api.StoreResult
import com.multistore.store.common.StoreErrors
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseHtml
import com.multistore.store.modyolo.ModyoloConfig
import com.multistore.store.modyolo.ModyoloRefs

/**
 * The two halves of a modyolo download: **the variant list** and **the file**.
 *
 * They live in two different places and neither is what it looks like.
 *
 * The list sits on the download page, inside a Bootstrap accordion: each entry is an anchor with
 * the version's name, and **the current entry has an empty body** because it is the page you are
 * already on. The link to the file is in neither.
 *
 * The file arrives from a POST to the WordPress AJAX endpoint with the variant's `Referer`. The
 * response is an HTML fragment containing the download anchor.
 */
internal class ModyoloDownloadParser(private val config: ModyoloConfig) {

    /** The file to download: URL already normalised, name, type, and the declared size. */
    data class File(
        val url: String,
        val fileName: String,
        val artifactType: ArtifactType,
        val declaredSize: Long?,
    )

    /** The fragment the AJAX call returns. */
    fun parseFile(fragment: String, baseUrl: String): StoreResult<File> {
        val parsed = parseHtml(fragment, baseUrl) { page ->
            val href = page.absUrl(config.selectors.ajaxDownloadLink, HREF)
            href to page.textOrNull(config.selectors.ajaxDownloadSize)
        }
        val (rawUrl, sizeLabel) = when (parsed) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return parsed
            StoreResult.Unsupported -> return StoreResult.Unsupported
        }

        val url = ModyoloRefs.normalizeFileUrl(rawUrl)
        if (!Urls.isSecureOrLoopback(url)) {
            return StoreResult.Failure(
                StoreErrors.parseFailure("${config.selectors.ajaxDownloadLink} (https)", url),
            )
        }

        val fileName = ModyoloRefs.fileNameOf(url)
        return StoreResult.Success(
            File(
                url = url,
                fileName = fileName,
                artifactType = Urls.artifactTypeOf(fileName),
                // The declared size is rounded to the binary megabyte. It is for display, never
                // for verification: see the note on the expected size in the adapter.
                declaredSize = TextValues.byteSize(sizeLabel),
            ),
        )
    }

    /**
     * The variants from the download page.
     *
     * The anchor **is** the variant's identity, and it is also the last segment of the URL serving
     * it. Nothing is constructed: the number modyolo wrote is read.
     *
     * The version name arrives with a leading `v` and sometimes a modification marker. The `v` is
     * removed because it is the store's typography; the rest **is kept**, because "MOD" next to the
     * number is information — it tells the user that variant is not the original build.
     */
    fun parseVersions(html: String, url: String): StoreResult<List<AppVersion>> =
        parseHtml(html, url) { page ->
            val listed = page.all(config.selectors.versionItem).mapNotNull { item ->
                // The heading and the panel are **siblings**, not nested: the variant number is in
                // the former's `href`, the size inside the latter. So the starting point is the
                // container enclosing both — otherwise the size would always come out absent, and
                // that would be a silently empty field, which is what `HtmlPage` exists to make
                // impossible.
                val toggle = item.oneOrNull(config.selectors.versionToggle) ?: return@mapNotNull null
                val anchor = toggle.ownAttrOrNull(HREF) ?: return@mapNotNull null
                val variant = ModyoloRefs.variantFromAnchor(anchor) ?: return@mapNotNull null
                val label = toggle.textOrNull() ?: return@mapNotNull null
                AppVersion(
                    versionName = label.removePrefix(VERSION_PREFIX).trim().ifBlank { label },
                    // No version code anywhere on the site, not even in the file name.
                    versionCode = null,
                    ref = ModyoloRefs.versionRef(variant),
                    artifactType = ArtifactType.APK,
                    sizeBytes = TextValues.byteSize(item.textOrNull(config.selectors.versionSize)),
                )
            }
            listed.ifEmpty { listOf(onlyVersion(page)) }
        }

    /**
     * The only version, when there is no accordion.
     *
     * **Not a rare case, and it cost an empty listing on the device.** On a post with a single
     * variant modyolo emits no "Other available link(s)" section at all: the list came out empty,
     * and the "Toolbox for Minecraft: PE" listing said "This store publishes no installable
     * package for this app" in front of a file that downloads perfectly well.
     *
     * The version name comes from the page heading, which writes both forms the same way:
     * `Minecraft - v1.26.50.24 MOD` and `Toolbox for Minecraft: PE - v5.4.54 - Mod`. It is cut at
     * the first ` - ` and the leading `v` dropped, which is typography; the rest **is kept**,
     * because "MOD" next to the number tells the user this is not the original build.
     *
     * No size: the page does not write one for the current variant — that number sits next to the
     * *others*, which by definition are absent here.
     */
    private fun onlyVersion(page: com.multistore.store.common.html.HtmlPage): AppVersion {
        val heading = page.text(config.selectors.downloadHeading)
        val name = heading.substringAfter(TITLE_SEPARATOR, heading)
            .removePrefix(VERSION_PREFIX)
            .trim()
            .ifBlank { heading }
        return AppVersion(
            versionName = name,
            versionCode = null,
            ref = ModyoloRefs.versionRef(ModyoloRefs.FIRST_VARIANT),
            artifactType = ArtifactType.APK,
        )
    }

    private companion object {
        const val HREF = "href"
        const val VERSION_PREFIX = "v"
        const val TITLE_SEPARATOR = " - "
    }
}
