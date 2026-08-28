package com.multistore.store.apkmody.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmody.ApkModyConfig
import com.multistore.store.apkmody.ApkModySelectors
import com.multistore.store.apkmody.ApkModyRefs
import com.multistore.store.apkmody.Fixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * apkmody's parsers against the real pages.
 *
 * The contract test exercises the adapter through the HTTP client; here the view is **inside**,
 * where the values that layer cannot distinguish live: a version code read from the wrong position
 * of a file name, a date the US format reads backwards, a category taken from the wrong breadcrumb
 * link.
 */
@DisplayName("Parsers — apkmody")
class ApkModyParsersTest {

    private val config = ApkModyConfig(baseUrl = BASE_URL)

    @Nested
    @DisplayName("Search")
    inner class Search {

        private val parser = ApkModySearchParser(config)

        @Test
        fun `reads the twenty cards and no footer link`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()

            assertThat(page.items).hasSize(SEARCH_RESULTS)
            assertThat(page.items.first().ref).isEqualTo(StoreAppRef(Fixtures.APP_PATH))
            assertThat(page.items.first().title).isEqualTo(Fixtures.APP_TITLE)
            assertThat(page.items.first().latestVersionName).isEqualTo(Fixtures.APP_LATEST_VERSION)
            // The footer lists several real apps under "Trending" and "Latest", with links of the
            // same shape. If they appeared, the results would be twenty-five and five of them would
            // always be the same, for any query.
            assertThat(page.items.count { it.title == NETFLIX }).isEqualTo(0)
        }

        @Test
        fun `tells apps from games, which is the only place the store says so`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()

            assertThat(page.items.any { it.contentKind == ContentKind.APP }).isTrue()
            assertThat(page.items.any { it.contentKind == ContentKind.GAME }).isTrue()
            assertThat(page.items.none { it.contentKind == ContentKind.UNKNOWN }).isTrue()
        }

        @Test
        fun `an empty search really is empty`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH_EMPTY), SEARCH_URL, page = 0).expect()
            assertThat(page.items).isEmpty()
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
            val broken = ApkModyConfig(
                selectors = ApkModySelectors(searchName = ".does-not-exist"),
            )
            val parser = ApkModySearchParser(broken)

            val result = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0)

            val error = (result as StoreResult.Failure).error as StoreError.ParseFailure
            assertThat(error.selector).isEqualTo(broken.selectors.searchItem)
        }
}

    @Nested
    @DisplayName("Listing")
    inner class Detail {

        private val parser = ApkModyDetailParser(config)
        private val ref = StoreAppRef(Fixtures.APP_PATH)

        @Test
        fun `reads the APP INFO table, not the header`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            assertThat(detail.summary.title).isEqualTo(Fixtures.APP_TITLE)
            assertThat(detail.summary.packageName).isEqualTo(Fixtures.APP_PACKAGE)
            assertThat(detail.summary.latestVersionName).isEqualTo(Fixtures.APP_LATEST_VERSION)
            assertThat(detail.summary.developer).isEqualTo(PUBLISHER)
            assertThat(detail.screenshots).hasSize(SCREENSHOTS)
            assertThat(detail.description.isEmpty).isFalse()
        }

        @Test
        fun `the description loses the sentence about the site, not the one about the app`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()
            val text = requireNotNull(detail.description.resolve(listOf("en")))

            // The two halves sit in the same paragraph, so removing the element would take away
            // what describes the modification too. The two assertions go together: on its own, the
            // first would pass even with everything deleted.
            assertThat(text).doesNotContain("Instead of repeating")
            assertThat(text).contains("bypass shuffle-only playback")
            assertThat(text).endsWith("without a monthly subscription.")
        }

        @Test
        fun `the category is the middle crumb, not the app itself`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            // `Home / Apps / music / Spotify Pro`: the category link and the app link have the
            // identical shape, and the only thing telling them apart is that one of the two is the
            // app being read.
            assertThat(detail.summary.categories).containsExactly(CATEGORY)
        }

        @Test
        fun `it does not attribute the four decorative stars to the app`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            // The rating block shows four stars out of five on **every** app measured. It is
            // graphics, not a rating, and reporting it as `4.0` next to another store's real `3.9`
            // would give it the air of a measurement.
            assertThat(detail.summary.rating).isNull()
            assertThat(detail.summary.ratingCount).isNull()
        }

        @Test
        fun `the 404 page produces no listing`() {
            val result = parser.parse(Fixtures.html(Fixtures.NOT_FOUND), DETAIL_URL, ref)

            // apkmody's 404 is a complete 226 KB page with menu, footer and trending apps: if the
            // parser found a title in it, the app would show a listing for something that does not
            // exist.
            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
            assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
        }
    }

    @Nested
    @DisplayName("History and download")
    inner class HistoryAndDownload {

        private val downloadParser = ApkModyDownloadParser(config)
        private val historyParser = ApkModyHistoryParser(config, downloadParser)

        @Test
        fun `the history carries the four rows and the current file`() {
            val history = historyParser.parse(Fixtures.html(Fixtures.HISTORY), HISTORY_URL).expect()

            assertThat(history.entries).hasSize(HISTORY_ENTRIES)
            assertThat(history.entries.first().versionName).isEqualTo(Fixtures.APP_LATEST_VERSION)
            assertThat(history.entries.map { it.segment }.toSet()).hasSize(HISTORY_ENTRIES)
            assertThat(history.latest?.fileName).isEqualTo(Fixtures.APP_LATEST_FILE)
        }

        @Test
        fun `the current row is already in the history, which is why it is not added`() {
            val history = historyParser.parse(Fixtures.html(Fixtures.HISTORY), HISTORY_URL).expect()

            // If it were not, the adapter would have to add it by hand and two rows would come out
            // for the same file, with two different refs the database cannot recognise as the same.
            // Verified on a second app too, which has a single row and it is the current one.
            assertThat(history.entries.map { it.versionName })
                .contains(history.latest?.versionName)
        }

        @Test
        fun `the rows' sizes and dates are read, with JavaScript's format`() {
            val history = historyParser.parse(Fixtures.html(Fixtures.HISTORY), HISTORY_URL).expect()

            // The date format is what JavaScript's `toDateString()` prints: without the right
            // format every row would have a null date, and "updated when?" would go unanswered
            // across the whole store.
            assertThat(history.entries.mapNotNull { it.publishedAt }).hasSize(HISTORY_ENTRIES)
            assertThat(history.entries.mapNotNull { it.sizeBytes }).hasSize(HISTORY_ENTRIES)
        }

        @Test
        fun `the download link is the CDN's and not apkmody's installer`() {
            val file = downloadParser.parse(Fixtures.html(Fixtures.DOWNLOAD), DOWNLOAD_URL).expect()

            assertThat(file.url).contains(ApkModyConfig.DEFAULT_DOWNLOAD_HOST)
            assertThat(file.url).doesNotContain(ADVERT_HOST)
            assertThat(file.packageName).isEqualTo(Fixtures.APP_PACKAGE)
        }

        @Test
        fun `without a file on the CDN it does not offer the advert next to it`() {
            // **This test exists because the previous one is not enough, and that was discovered by
            // removing the host filter: the suite stayed green.** On the real page the CDN anchor is
            // the first in the list, so "take the first" and "take the one on the CDN" give the same
            // result, and the filter looks decorative.
            //
            // The case where it is not is an app with no file: the download list would contain
            // **only** "Use APKMODY App", which points at their own installer, has the same markup
            // and carries the icon of the app being downloaded. Without the filter, MultiStore
            // would install apkmody's installer believing it was installing the app — and the
            // package block would stop it after 135 MB, which is late.
            //
            // No page on apkmody is (yet) in that state, and inventing one wholesale would prove
            // little. It is obtained instead from the **real** page with one declared substitution:
            // the CDN's host becomes the advert's.
            val hostile = Fixtures.html(Fixtures.DOWNLOAD)
                .replace(ApkModyConfig.DEFAULT_DOWNLOAD_HOST, ADVERT_HOST)

            val result = downloadParser.parse(hostile, DOWNLOAD_URL)

            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
            assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
        }

        @Test
        fun `the file name declares version and version code, and is read from the right`() {
            val file = downloadParser.parse(Fixtures.html(Fixtures.DOWNLOAD), DOWNLOAD_URL).expect()

            // The app's name contains an underscore, so counting fields from the left would give
            // the wrong version. From the right they are always the same three — version, version
            // code, hash.
            assertThat(file.versionName).isEqualTo(Fixtures.APP_LATEST_VERSION)
            assertThat(file.versionCode).isEqualTo(Fixtures.APP_LATEST_VERSION_CODE)
        }

        @Test
        fun `a version's page serves that version's file`() {
            val file = downloadParser.parse(Fixtures.html(Fixtures.HISTORY_VERSION), VERSION_URL).expect()

            assertThat(file.versionName).isEqualTo(Fixtures.OLD_VERSION_NAME)
            assertThat(file.versionCode).isEqualTo(Fixtures.OLD_VERSION_CODE)
        }

        @Test
        fun `the 404 page produces no file`() {
            val result = downloadParser.parse(Fixtures.html(Fixtures.NOT_FOUND), DOWNLOAD_URL)
            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        }
    }

    @Nested
    @DisplayName("Refs")
    inner class Refs {

        @Test
        fun `a malformed ref does not become a URL`() {
            // The contract test hands every adapter `../../etc/passwd?<script>&%00`: without the
            // validation it would end up concatenated into a URL towards the store.
            assertThat(ApkModyRefs.appPath(StoreAppRef("../../etc/passwd"))).isNull()
            assertThat(ApkModyRefs.appPath(StoreAppRef("spotify-pro"))).isNull()
            assertThat(ApkModyRefs.appPath(StoreAppRef("blog/spotify-pro"))).isNull()
            assertThat(ApkModyRefs.appPath(StoreAppRef(Fixtures.APP_PATH))).isEqualTo(Fixtures.APP_PATH)
        }

        @Test
        fun `an unrecognised version segment falls back to the current version`() {
            assertThat(ApkModyRefs.versionSegment(null)).isEqualTo(ApkModyConfig.DOWNLOAD_SEGMENT)
            assertThat(ApkModyRefs.versionSegment(com.multistore.core.model.VersionRef("../../etc")))
                .isEqualTo(ApkModyConfig.DOWNLOAD_SEGMENT)
            assertThat(ApkModyRefs.versionSegment(com.multistore.core.model.VersionRef(Fixtures.OLD_VERSION_SEGMENT)))
                .isEqualTo(Fixtures.OLD_VERSION_SEGMENT)
        }

        @Test
        fun `the version code is read even when the app name contains underscores`() {
            assertThat(ApkModyRefs.versionCodeFromFileName("Spotify_Pro_9.1.36.1948_151061948_eca7c8.apk"))
                .isEqualTo(Fixtures.APP_LATEST_VERSION_CODE)
            assertThat(ApkModyRefs.versionCodeFromFileName("KLMS_Agent_1.4.05_140500020_5e246e.apk"))
                .isEqualTo(KLMS_VERSION_CODE)
            // Verified **against the real APK**: downloaded and read with `aapt2 dump badging`,
            // the file name's fields match the declared version code and version name.
            assertThat(ApkModyRefs.versionCodeFromFileName("ZX-FLY_1.0.2_6_235298.apk")).isEqualTo(6L)
            assertThat(ApkModyRefs.versionNameFromFileName("ZX-FLY_1.0.2_6_235298.apk")).isEqualTo("1.0.2")
            // A name that does not have the expected shape produces no random number.
            assertThat(ApkModyRefs.versionCodeFromFileName("qualcosa.apk")).isNull()
            assertThat(ApkModyRefs.versionNameFromFileName("uno_due_tre_quattro.apk")).isNull()
        }
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixture, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixture, gave Unsupported")
    }

    private companion object {
        const val BASE_URL = "https://apkmody.mobi"
        const val SEARCH_URL = "$BASE_URL/?s=spotify"
        const val DETAIL_URL = "$BASE_URL/apps/spotify-pro"
        const val DOWNLOAD_URL = "$BASE_URL/apps/spotify-pro/download"
        const val HISTORY_URL = "$BASE_URL/apps/spotify-pro/history"
        const val VERSION_URL = "$BASE_URL/apps/spotify-pro/history/xyTAa4R6VE"
        const val ADVERT_HOST = "appstore.jooyfun.com"
        const val PUBLISHER = "Spotify AB"
        const val CATEGORY = "music"
        const val NETFLIX = "Netflix"
        const val SEARCH_RESULTS = 20
        const val HISTORY_ENTRIES = 4
        const val SCREENSHOTS = 6
        const val KLMS_VERSION_CODE = 140500020L
    }
}
