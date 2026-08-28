package com.multistore.store.an1.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.store.an1.An1Config
import com.multistore.store.an1.An1Selectors
import com.multistore.store.an1.Fixtures
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * an1's parsers against the real pages, field by field.
 *
 * The contract test checks the adapter **behaves** as declared; here what is checked is that it
 * reads the right values, and above all that it **fails** when it should. The second half is the
 * one that counts: a test that passes both with and without the defence it is meant to prove is
 * not a test, it is a caption.
 *
 * Every broken selector is therefore injected **through the configuration**, which is also how it
 * could break for real: a published document with a typo.
 */
@DisplayName("Parsers — an1")
class An1ParsersTest {

    private val config = An1Config()
    private val searchParser = An1SearchParser(config)
    private val detailParser = An1DetailParser(config)
    private val downloadParser = An1DownloadParser(config)

    private val searchUrl = "${An1Config.DEFAULT_BASE_URL}/index.php?do=search"
    private val detailUrl = "${An1Config.DEFAULT_BASE_URL}/${Fixtures.APP_REF}.html"
    private val downloadUrl = "${An1Config.DEFAULT_BASE_URL}/file_${Fixtures.APP_ID}-dw.html"

    @Nested
    @DisplayName("search")
    inner class Search {

        @Test
        @DisplayName("reads title, developer, icon and rating of every row")
        fun readsEveryField() {
            val page = searchParser.parse(Fixtures.html(Fixtures.SEARCH), searchUrl, page = 0).expect()

            val first = page.items.first()
            assertThat(first.title).isEqualTo(Fixtures.GAME_TITLE)
            assertThat(first.developer).isEqualTo("Blockman GO Studio")
            assertThat(first.iconUrl).startsWith("https://an1.com/uploads/")
            // The rating sits in the `li`'s **text**, not in the style's percentage.
            assertThat(first.rating).isWithin(TOLERANCE).of(EXPECTED_RATING)
            // No package: a property of the site, not a gap in the row.
            assertThat(page.items.mapNotNull { it.packageName }).isEmpty()
        }

        @Test
        @DisplayName("a broken row selector is a parse failure, not zero results")
        fun aBrokenRowSelectorFails() {
            // The defect found on the emulator by publishing a signed document with a wrong
            // selector: search answered with no results and no errors, and no part of the app could
            // say anything was wrong.
            val broken = An1Config(selectors = An1Selectors(searchLink = ".does-not-exist a[href]"))
            val result = An1SearchParser(broken).parse(Fixtures.html(Fixtures.SEARCH), searchUrl, 0)

            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
            assertThat((result as StoreResult.Failure).error)
                .isInstanceOf(StoreError.ParseFailure::class.java)
        }

        @Test
        @DisplayName("zero rows stay zero results, not an error")
        fun noRowsIsNotAFailure() {
            // The distinction matters: with nine stores in parallel, a "no results" treated as an
            // error would trip the circuit breaker of a perfectly healthy store.
            val page = searchParser.parse(Fixtures.html(Fixtures.SEARCH_EMPTY), searchUrl, 0).expect()

            assertThat(page.items).isEmpty()
            assertThat(page.hasMore).isFalse()
        }
    }

    @Nested
    @DisplayName("detail")
    inner class Detail {

        @Test
        @DisplayName("reads the listing's microdata")
        fun readsTheMicrodata() {
            val detail = detailParser.parse(
                Fixtures.html(Fixtures.DETAIL),
                detailUrl,
                StoreAppRef(Fixtures.APP_REF),
            ).expect()

            assertThat(detail.summary.title).isEqualTo(Fixtures.APP_TITLE)
            assertThat(detail.summary.developer).isEqualTo(Fixtures.APP_DEVELOPER)
            assertThat(detail.summary.latestVersionName).isEqualTo(Fixtures.APP_VERSION)
            assertThat(detail.summary.ratingCount).isEqualTo(EXPECTED_RATING_COUNT)
            assertThat(detail.summary.lastUpdated).isNotNull()
            assertThat(detail.description.byTag.values.single()).contains("instant messengers")
        }

        @Test
        @DisplayName("the title is `Telegram`, not the page heading")
        fun theTitleIsTheNameAndNotTheHeadline() {
            val detail = detailParser.parse(
                Fixtures.html(Fixtures.DETAIL),
                detailUrl,
                StoreAppRef(Fixtures.APP_REF),
            ).expect()

            // The headline element reads "Download Telegram 12.4.3 free on android". It is the most
            // visible place on the page and the wrong one: that text would end up in "My apps" and
            // in the update notification. The name lives in the microdata.
            assertThat(detail.summary.title).doesNotContain("Download")
            assertThat(Fixtures.html(Fixtures.DETAIL)).contains("Download Telegram")
        }

        @Test
        @DisplayName("one version, with the size but without a version code")
        fun oneVersionWithoutAVersionCode() {
            val detail = detailParser.parse(
                Fixtures.html(Fixtures.DETAIL),
                detailUrl,
                StoreAppRef(Fixtures.APP_REF),
            ).expect()

            assertThat(detail.versions).hasSize(1)
            val version = detail.versions.single()
            assertThat(version.versionName).isEqualTo(Fixtures.APP_VERSION)
            // an1 publishes no version code anywhere. Deriving it from the version name would give
            // a number that is not the APK's, and the anti-downgrade rule would compare it with the
            // installed one.
            assertThat(version.versionCode).isNull()
            // `79.9Mb` -> about 83.7 MB in binary units. For display, not for verification.
            assertThat(version.sizeBytes).isGreaterThan(0L)
            // `Android 5.0` -> API 21, from the release table.
            assertThat(version.minSdk).isEqualTo(EXPECTED_MIN_SDK)
        }
    }

    @Nested
    @DisplayName("download")
    inner class Download {

        @Test
        @DisplayName("picks the app's file out of the page's two `.apk` links")
        fun picksTheAppAndNotTheirStore() {
            val file = downloadParser.parse(Fixtures.html(Fixtures.DOWNLOAD), downloadUrl).expect()

            assertThat(file.fileName).isEqualTo(Fixtures.APP_FILE)
            assertThat(file.url).endsWith(Fixtures.APP_FILE)
        }

        @Test
        @DisplayName("without the precise anchor it does not fall back to the first `.apk` found")
        fun doesNotFallBackToTheFirstApk() {
            // The defence to prove is exactly this: the decoy sits **before** the real file in the
            // HTML, on the **same host**. A parser taking "the first link to an .apk" — or
            // filtering only by host — would serve an1's own store app instead of the app.
            val broken = An1Config(selectors = An1Selectors(downloadLink = "a[href$=.apk]"))
            val file = An1DownloadParser(broken)
                .parse(Fixtures.html(Fixtures.DOWNLOAD), downloadUrl)
                .expect()

            // With the generic selector the decoy comes out: proof that the precise selector is
            // needed, and that this test really checks it.
            assertThat(file.fileName).isEqualTo("an1store.apk")

            // And with the real one, no.
            assertThat(downloadParser.parse(Fixtures.html(Fixtures.DOWNLOAD), downloadUrl).expect().fileName)
                .isEqualTo(Fixtures.APP_FILE)
        }
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixtures, gave Unsupported")
    }

    private companion object {
        const val TOLERANCE = 0.01f
        const val EXPECTED_RATING = 3.8f
        const val EXPECTED_RATING_COUNT = 2284
        const val EXPECTED_MIN_SDK = 21
    }
}
