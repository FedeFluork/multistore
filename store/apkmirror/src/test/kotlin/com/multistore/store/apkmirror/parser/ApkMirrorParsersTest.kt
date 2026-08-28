package com.multistore.store.apkmirror.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.apkmirror.ApkMirrorSelectors
import com.multistore.store.apkmirror.Fixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * apkmirror's parsers against the **real** pages, value by value.
 *
 * This store publishes the most valuable data of the nine and publishes it in the way easiest to
 * misread: two SHA-256s in the same panel, the version code in light grey with no label, search
 * results with the same markup as the sidebar. Every test below corresponds to a specific way of
 * getting it wrong.
 */
@DisplayName("apkmirror parsers")
class ApkMirrorParsersTest {

    private val config = ApkMirrorConfig(baseUrl = BASE)

    @Nested
    @DisplayName("Search")
    inner class Search {

        private val parser = ApkMirrorSearchParser(config)

        @Test
        fun `takes the ten results and not the thirty-eight from the sidebar`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()

            // Ten: what apkmirror serves per page. A higher number here would mean the parser is
            // collecting sidebar widgets.
            assertThat(page.items).hasSize(10)
            assertThat(page.items.first().title).isEqualTo("Disa - Message hub for SMS, Telegram, FB Messenger")
        }

        @Test
        fun `the page with no results is empty despite the thirty-eight rows`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH_EMPTY), SEARCH_URL, page = 0).expect()

            // The fixture contains 38 rows of that class, all sidebar. It is the proof that the
            // container, and not the class, is what distinguishes a result.
            assertThat(page.items).isEmpty()
            assertThat(page.hasMore).isFalse()
        }

        @Test
        fun `the icon is the original, not the resizer's thumbnail`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()

            val icons = page.items.mapNotNull { it.iconUrl }
            assertThat(icons).isNotEmpty()
            // The resize endpoint would serve a 32-pixel image to a 3x screen.
            icons.forEach {
                assertThat(it).doesNotContain("ap_resize")
                assertThat(it).startsWith("https://")
            }
        }

        @Test
        fun `no result carries an invented packageName`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()

            // apkmirror does not write the package in results: it is only on the listing. Hence
            // `providesPackageName = false`, which looks timid and is exact.
            assertThat(page.items.mapNotNull { it.packageName }).isEmpty()
        }
    
        /**
         * A wrong row selector is **not** a search with no results.
         *
         * It is what a published document with a typo produces, and it used to be invisible: the
         * page arrives, the container finds its rows, none produces a result, and the search
         * answers "nothing" as though the store did not have that app. The broken selector is
         * injected **into the configuration**, which is the same route a remote override takes.
         */
        @Test
        fun `rows found and none readable is a parse failure, not zero results`() {
            val broken = ApkMirrorConfig(
                selectors = ApkMirrorSelectors(searchTitleLink = ".does-not-exist a"),
            )
            val parser = ApkMirrorSearchParser(broken)

            val result = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0)

            val error = (result as StoreResult.Failure).error as StoreError.ParseFailure
            assertThat(error.selector).isEqualTo(broken.selectors.searchRow)
        }
}

    @Nested
    @DisplayName("App listing")
    inner class App {

        private val parser = ApkMirrorAppParser(config)
        private val ref = StoreAppRef(Fixtures.APP_PATH)

        @Test
        fun `the packageName comes from the Play Store link, the only place it exists`() {
            val app = parser.parse(Fixtures.html(Fixtures.APP), APP_URL, ref).expect()

            assertThat(app.detail.summary.packageName).isEqualTo(Fixtures.APP_PACKAGE)
            assertThat(app.detail.summary.title).isEqualTo(Fixtures.APP_TITLE)
            assertThat(app.detail.summary.developer).isEqualTo("Mozilla")
        }

        @Test
        fun `the releases are this app's, not the side widgets'`() {
            val app = parser.parse(Fixtures.html(Fixtures.APP), APP_URL, ref).expect()

            assertThat(app.releases).isNotEmpty()
            // The filter is not on the "All versions" heading — English text that changes when
            // apkmirror changes its copy — but on the shape of the URL.
            app.releases.forEach { assertThat(it.path).startsWith("${Fixtures.APP_PATH}/") }
            assertThat(app.releases.first().path).isEqualTo(Fixtures.RELEASE_PATH)
        }

        @Test
        fun `the description is there, and does not carry the advert that splits it in two`() {
            val app = parser.parse(Fixtures.html(Fixtures.APP), APP_URL, ref).expect()
            val text = requireNotNull(app.detail.description.resolve(listOf("en")))

            // The three assertions are one defence each and none covers the others: the first says
            // the right panel was found, the second that the advertising block wedged **between
            // the first and second paragraph** is gone, the third that the "More/Less" control at
            // the end did not end up in the text.
            assertThat(text).contains("Firefox is a fast, private browser")
            assertThat(text).doesNotContain("Advertisement")
            assertThat(text).doesNotContain("Remove ads, dark theme")
        }

        @Test
        fun `the description is the panel's, not the release notes`() {
            val app = parser.parse(Fixtures.html(Fixtures.APP), APP_URL, ref).expect()
            val text = requireNotNull(app.detail.description.resolve(listOf("en")))

            // The page has **two** notes blocks, in this order: first the release notes, then the
            // description. A selector without the anchor takes the first, and the listing would
            // show the changelog in place of the description — with no error, which is how this
            // mistake would stay there forever.
            assertThat(text).doesNotContain("Behind-the-scenes updates")
            assertThat(text.length).isGreaterThan(SHORT_SUMMARY_CHARS)
        }

        @Test
        fun `the screenshots are there and are full resolution`() {
            val app = parser.parse(Fixtures.html(Fixtures.APP), APP_URL, ref).expect()

            assertThat(app.detail.screenshots).isNotEmpty()
            app.detail.screenshots.forEach { assertThat(it.url).startsWith("https://") }
        }
    }

    @Nested
    @DisplayName("Release page")
    inner class Release {

        private val parser = ApkMirrorReleaseParser(config)

        @Test
        fun `every variant carries version code, type, ABI and minSdk`() {
            val variants = parser.parse(
                Fixtures.html(Fixtures.RELEASE),
                RELEASE_URL,
                Fixtures.RELEASE_PATH,
            ).expect()

            assertThat(variants).hasSize(9)
            variants.forEach {
                assertThat(it.versionCode).isNotNull()
                assertThat(it.minSdk).isNotNull()
            }
            // **Three distinct version codes under one release name.** The publisher encodes the
            // ABI into it, so propagating one variant's code to the others — the shortcut that
            // looks obvious when only two pages are in view — would give half the artifacts a
            // number that is not theirs.
            assertThat(variants.mapNotNull { it.versionCode }.distinct()).hasSize(3)
            assertThat(variants.mapNotNull { it.versionCode }).contains(Fixtures.VERSION_CODE)
            assertThat(variants.count { it.artifactType == ArtifactType.APK }).isEqualTo(3)
            assertThat(variants.count { it.artifactType == ArtifactType.APKM }).isEqualTo(6)
        }

        @Test
        fun `the version code is not the year of the date next to it`() {
            val variants = parser.parse(
                Fixtures.html(Fixtures.RELEASE),
                RELEASE_URL,
                Fixtures.RELEASE_PATH,
            ).expect()

            // In the same cell, with the **same class**, sit the version code and a date. Taking
            // the first number of at least three digits would give the year the day the two swap
            // order.
            assertThat(variants.mapNotNull { it.versionCode }).doesNotContain(2026L)
            variants.mapNotNull { it.versionCode }.forEach { assertThat(it).isGreaterThan(1_000_000_000L) }
        }

        @Test
        fun `Android 12L becomes API 32, not 31`() {
            val variants = parser.parse(
                Fixtures.html(Fixtures.RELEASE),
                RELEASE_URL,
                Fixtures.RELEASE_PATH,
            ).expect()

            // The `Android 12L+` row really exists in this table. Reading the `L` as noise would
            // give `minSdk = 31`, i.e. an app declared installable on a device where it is not.
            assertThat(variants.mapNotNull { it.minSdk }).contains(32)
        }

        @Test
        fun `the header row does not become a variant`() {
            val variants = parser.parse(
                Fixtures.html(Fixtures.RELEASE),
                RELEASE_URL,
                Fixtures.RELEASE_PATH,
            ).expect()

            // The table's first row is the header and has no link: were it to appear among the
            // results, the listing would show a phantom version.
            assertThat(variants.map { it.versionName }).doesNotContain("Variant")
            variants.forEach { assertThat(it.path).startsWith("${Fixtures.RELEASE_PATH}/") }
        }
    }

    @Nested
    @DisplayName("Variant page")
    inner class Variant {

        private val parser = ApkMirrorVariantParser(config)

        @Test
        fun `a single APK carries both the file hash and the certificate hash`() {
            val detail = parser.parse(Fixtures.html(Fixtures.VARIANT_APK), VARIANT_URL).expect()

            // **The two hashes must not be swapped.** Both sections of the download panel contain
            // a 64-character SHA-256; taking the first would give the certificate's fingerprint in
            // place of the file's, and pre-install verification would always fail, for the wrong
            // reason.
            assertThat(detail.fileSha256).isEqualTo(Sha256.parseOrNull(Fixtures.FILE_SHA256))
            assertThat(detail.signerSha256).isEqualTo(Sha256.parseOrNull(Fixtures.SIGNER_SHA256))
            assertThat(detail.fileSha256).isNotEqualTo(detail.signerSha256)
        }

        @Test
        fun `reads package, version, size to the byte and both SDKs`() {
            val detail = parser.parse(Fixtures.html(Fixtures.VARIANT_APK), VARIANT_URL).expect()

            assertThat(detail.packageName).isEqualTo(Fixtures.APP_PACKAGE)
            assertThat(detail.versionName).isEqualTo("154.0")
            assertThat(detail.versionCode).isEqualTo(Fixtures.VERSION_CODE)
            // `595.68 MB (624,620,840 bytes)`: the exact count wins, not the rounding.
            assertThat(detail.sizeBytes).isEqualTo(Fixtures.FILE_SIZE_BYTES)
            assertThat(detail.minSdk).isEqualTo(26)
            assertThat(detail.targetSdk).isEqualTo(37)
        }

        @Test
        fun `the bundle has the certificate but not the file hash, and says so`() {
            val detail = parser.parse(Fixtures.html(Fixtures.VARIANT_BUNDLE), VARIANT_URL).expect()

            // A bundle has no single file to hash, so apkmirror does not publish that section at
            // all. `null` is the right answer: it is why the capability says SOMETIMES and not
            // ALWAYS.
            assertThat(detail.fileSha256).isNull()
            assertThat(detail.signerSha256).isEqualTo(Sha256.parseOrNull(Fixtures.SIGNER_SHA256))
            assertThat(detail.versionCode).isEqualTo(Fixtures.VERSION_CODE)
        }

        @Test
        fun `the download button leads to the interstitial page`() {
            val detail = parser.parse(Fixtures.html(Fixtures.VARIANT_APK), VARIANT_URL).expect()

            assertThat(detail.downloadUrl).isNotNull()
            assertThat(detail.downloadUrl).contains("/download/")
            assertThat(detail.downloadUrl).contains("key=")
        }
    }

    @Nested
    @DisplayName("Interstitial")
    inner class Interstitial {

        private val parser = ApkMirrorInterstitialParser(config)

        @Test
        fun `extracts the final link, with a key different from the starting one`() {
            val url = parser.parse(Fixtures.html(Fixtures.INTERSTITIAL), INTERSTITIAL_URL).expect()

            assertThat(url).contains("download.php")
            // The interstitial's key and the variant page's are two different keys, which is why
            // this page has to be really opened instead of composing the next URL.
            assertThat(url).contains("key=bffead60e7dcc5c9274789010e50891171c23dea")
            assertThat(url).doesNotContain("key=6da547da283799e915ae42ef50f51fe457f6f8d1")
        }
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixture, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixture, gave Unsupported")
    }

    private companion object {
        const val BASE = "https://www.apkmirror.com"

        /** The other notes block's summary fits on one line: the real description does not. */
        const val SHORT_SUMMARY_CHARS = 200
        const val SEARCH_URL = "$BASE/?post_type=app_release&searchtype=app&s=telegram"
        const val APP_URL = "$BASE/apk/mozilla/firefox/"
        const val RELEASE_URL = "$BASE/apk/${Fixtures.RELEASE_PATH}/"
        const val VARIANT_URL = "$BASE/apk/${Fixtures.VARIANT_APK_PATH}/"
        const val INTERSTITIAL_URL = "${VARIANT_URL}download/?key=6da547da283799e915ae42ef50f51fe457f6f8d1"
    }
}
