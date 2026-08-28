package com.multistore.store.apkmirror.parser

import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.apkmirror.ApkMirrorRefs
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseHtmlOrNotFound
import kotlin.time.Instant

/** A release listed on the listing page: `154.0`, with its variants page. */
internal data class ApkMirrorRelease(
    val path: String,
    val label: String,
    val publishedAt: Instant?,
)

/** The app's listing plus the list of its releases. */
internal data class ApkMirrorApp(
    val detail: StoreListingDetail,
    val releases: List<ApkMirrorRelease>,
)

/**
 * An app's listing on apkmirror: `/apk/{developer}/{app}/`.
 *
 * ### The `packageName` is there, but not where you would expect
 *
 * apkmirror writes it nowhere as a datum: it is read from the **Play Store link** at the foot of
 * the listing. That is the only place on the page it appears, and it is absent entirely from
 * search results — hence a `providesPackageName` that concerns the listing and not the list.
 *
 * ### Releases are recognised by the shape of their URL
 *
 * The page has six list widgets, and only one is "All versions": the others are sidebar with the
 * same markup. Rather than anchoring to the heading — which is English text and changes when
 * apkmirror changes its copy — the filter is on the URL: a release is a **three**-segment path
 * under `/apk/` starting with this app's path. The sidebar widgets' rows point at other apps and
 * fall away by themselves.
 */
internal class ApkMirrorAppParser(private val config: ApkMirrorConfig) {

    fun parse(html: String, url: String, ref: StoreAppRef): StoreResult<ApkMirrorApp> =
        parseHtmlOrNotFound(html, url) { document ->
            val appPath = ApkMirrorRefs.appPath(ref) ?: return@parseHtmlOrNotFound null
            val title = document.textOrNull(config.selectors.appTitle) ?: return@parseHtmlOrNotFound null

            ApkMirrorApp(
                detail = StoreListingDetail(
                    summary = StoreListingSummary(
                        storeId = StoreId.APKMIRROR,
                        ref = ref,
                        title = title,
                        packageName = packageNameOf(document),
                        developer = document.textOrNull(config.selectors.appDeveloper),
                        iconUrl = iconOf(document),
                    ),
                    description = LocalizedText.of(
                        document.textOrNull(
                            config.selectors.appDescription,
                            config.selectors.appDescriptionNoise,
                        ),
                    ),
                    screenshots = document.all(config.selectors.appScreenshot)
                        .mapNotNull { it.ownAbsUrlOrNull("src") }
                        .distinct()
                        .map { Screenshot(url = it) },
                ),
                releases = releasesOf(document, appPath),
            )
        }

    /** From the "View on Play Store" link, the only place apkmirror writes the package. */
    private fun packageNameOf(document: HtmlPage): String? {
        val href = document.absUrlOrNull(config.selectors.appPlayStoreLink, "href") ?: return null
        return Urls.queryParam(href, PLAY_ID_PARAM)?.takeIf { it.contains('.') }
    }

    /**
     * The real icon, not the resizer's thumbnail.
     *
     * As in search results, apkmirror serves images through a resize endpoint: on a 3x phone
     * screen that thumbnail would look blurry.
     */
    private fun iconOf(document: HtmlPage): String? {
        val raw = document.absUrlOrNull(config.selectors.appIcon, "src") ?: return null
        return Urls.queryParam(raw, RESIZE_PARAM) ?: raw
    }

    private fun releasesOf(document: HtmlPage, appPath: String): List<ApkMirrorRelease> =
        document.all(config.selectors.appReleaseRow).mapNotNull { row ->
            val link = row.oneOrNull(config.selectors.appReleaseLink) ?: return@mapNotNull null
            val href = link.ownAbsUrlOrNull("href") ?: return@mapNotNull null
            val path = ApkMirrorRefs.contentPath(href) ?: return@mapNotNull null
            val segments = ApkMirrorRefs.contentSegments(href) ?: return@mapNotNull null
            if (segments.size != RELEASE_SEGMENTS) return@mapNotNull null
            if (!path.startsWith("$appPath/")) return@mapNotNull null

            ApkMirrorRelease(
                path = path,
                label = link.ownTextOrNull() ?: return@mapNotNull null,
                publishedAt = TextValues.utcDateTime(
                    row.attrOrNull(config.selectors.searchDate, "data-utcdate"),
                ),
            )
        }.distinctBy { it.path }

    private companion object {
        const val PLAY_ID_PARAM = "id"
        const val RESIZE_PARAM = "src"

        /** `{developer}/{app}/{release}` under `/apk/`. */
        const val RELEASE_SEGMENTS = 3
    }
}
