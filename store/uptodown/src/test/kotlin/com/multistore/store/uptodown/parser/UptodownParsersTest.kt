package com.multistore.store.uptodown.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.uptodown.Fixtures
import com.multistore.store.uptodown.UptodownConfig
import com.multistore.store.uptodown.UptodownSelectors
import com.multistore.store.uptodown.UptodownRefs
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * uptodown's parsers against the real pages.
 *
 * The contract test exercises the adapter through the HTTP client; here we look inside, where the
 * values that layer cannot tell apart live: the right cell of a three-cell row, a "Rating" label
 * that is an age classification, a hash that is not a hash.
 */
@DisplayName("Parsers — uptodown")
class UptodownParsersTest {

    private val config = UptodownConfig()
    private val tables = UptodownTables(config)
    private val refs = UptodownRefs(config)

    @Nested
    @DisplayName("Search")
    inner class Search {

        private val parser = UptodownSearchParser(config, refs)

        @Test
        fun `reads the thirty-six results with author and icon`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()

            assertThat(page.items).hasSize(SEARCH_RESULTS)
            val first = page.items.first()
            assertThat(first.ref).isEqualTo(StoreAppRef(Fixtures.APP_SLUG))
            assertThat(first.title).isEqualTo(Fixtures.APP_TITLE)
            assertThat(first.developer).isEqualTo(DEVELOPER)
            assertThat(first.iconUrl).startsWith("https://img.utdstc.com/icon/")
        }

        @Test
        fun `the page with no results does not return the twelve suggested cards`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH_EMPTY), SEARCH_URL, page = 0).expect()

            // The page contains twelve `.item`s with markup identical to the results', under "Apps
            // you're gonna love" and outside `#content-list`. Telegram is among them: if the
            // selector were not anchored to the container, this test would find twelve results for
            // a query that found nothing.
            assertThat(page.items).isEmpty()
        }

        /**
         * A wrong row selector is **not** a search without results.
         *
         * It is the case a `parsers.json` with a typo produces, and it used to be invisible: the
         * page arrives, the container finds its thirty-six rows, none produces a result, and the
         * search answers "nothing" as though the store did not have that app. Seen on the emulator
         * before the fix — see the note on `mapRowsOrFail`.
         *
         * The broken selector is injected **into the configuration**, not into the code: that is
         * the same road a remote override travels, so this test also proves the configuration
         * really reaches the parser.
         */
        @Test
        fun `rows found and none readable is a ParseFailure, not zero results`() {
            val broken = UptodownConfig(
                selectors = UptodownSelectors(searchLink = ".does-not-exist a[href]"),
            )
            val parser = UptodownSearchParser(broken, UptodownRefs(broken))

            val result = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0)

            val error = (result as StoreResult.Failure).error as StoreError.ParseFailure
            assertThat(error.selector).isEqualTo(broken.selectors.searchItem)
        }

        /** The page with no results stays with no results: the container has no rows to read. */
        @Test
        fun `the page with no results does not become an error`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH_EMPTY), SEARCH_URL, page = 0).expect()

            assertThat(page.items).isEmpty()
        }
    }

    @Nested
    @DisplayName("Listing")
    inner class Detail {

        private val parser = UptodownDetailParser(config, tables)
        private val ref = StoreAppRef(Fixtures.APP_SLUG)

        @Test
        fun `reads the info tables, value cell included`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            // Every row is `<td>icon</td><th>label</th><td>value</td>`: taking the first `<td>`
            // gives an empty string, the row is discarded as "valueless", and the listing comes out
            // with no `packageName` and no SHA-256 — with no error at all.
            assertThat(detail.listing.summary.packageName).isEqualTo(Fixtures.APP_PACKAGE)
            assertThat(detail.currentFile.sha256).isEqualTo(Sha256.parseOrNull(Fixtures.CURRENT_SHA256))
            assertThat(detail.currentFileId).isEqualTo(Fixtures.CURRENT_FILE_ID)
            assertThat(detail.currentFile.artifactType).isEqualTo(ArtifactType.APK)
            assertThat(detail.listing.license).isEqualTo(LICENSE)
        }

        @Test
        fun `the rating is the star score, not the age classification`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            // The tables have a "Rating" row worth `+12`: that is the age classification. The
            // score is in `#rating-inner-text` and goes through no table. Reading the wrong row
            // would give every app one and a half stars — and would `TextValues.rating` accept
            // `12`? No: it discards it as outside 0..5. So the defect would be a **null** rating
            // across the whole store, which is harder to notice.
            assertThat(detail.listing.summary.rating).isEqualTo(RATING)
            assertThat(detail.listing.summary.ratingCount).isEqualTo(RATING_COUNT)
        }

        @Test
        fun `the screenshots are the large ones, not the thumbnails`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            assertThat(detail.listing.screenshots).hasSize(SCREENSHOTS)
            // `src` is the 150 px version, `data-src-large` the 800 px one. With the first, the
            // full-screen gallery would show blurry images — a defect visible only by opening a
            // screenshot, never in a test checking "the URL is not null".
            detail.listing.screenshots.forEach { assertThat(it.url).endsWith(LARGE_SUFFIX) }
        }

        @Test
        fun `it does not treat the file identifier as a version code`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            // `data-version-id` is 1,195,732,851 and is not a version code: it is the file's id in
            // uptodown's archive, and it grows over time across all apps together. Using it as a
            // version code would give an anti-downgrade rule comparing numbers unrelated to the
            // system's — and every app would look updatable.
            assertThat(detail.listing.summary.latestVersionCode).isNull()
            assertThat(detail.listing.summary.latestVersionName).isEqualTo(Fixtures.CURRENT_VERSION)
        }

        @Test
        fun `the 404 page produces no listing`() {
            val result = parser.parse(Fixtures.html(Fixtures.NOT_FOUND), DETAIL_URL, ref)

            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
            assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
        }
    }

    @Nested
    @DisplayName("Versions and download")
    inner class VersionsAndDownload {

        private val versionsParser = UptodownVersionsParser(config, tables)
        private val downloadParser = UptodownDownloadParser(config, tables)

        @Test
        fun `the versions page carries twenty files, the listing only six`() {
            val full = versionsParser.parse(Fixtures.html(Fixtures.VERSIONS), VERSIONS_URL).expect()
            val embedded = versionsParser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL).expect()

            assertThat(full).hasSize(VERSIONS)
            assertThat(embedded).hasSize(EMBEDDED_VERSIONS)
            // And the current one is **missing** from the list at the foot of the listing: that is
            // why the second request is not optional.
            assertThat(embedded.map { it.versionId }).doesNotContain(Fixtures.CURRENT_FILE_ID)
            assertThat(full.map { it.versionId }).contains(Fixtures.CURRENT_FILE_ID)
        }

        @Test
        fun `the minSdk is read despite the plus before the number`() {
            val full = versionsParser.parse(Fixtures.html(Fixtures.VERSIONS), VERSIONS_URL).expect()

            // `Android + 5.0`. Without allowing the sign before the number, `TextValues.apiLevel`
            // would return `null` on every row, and `VersionSelection` would consider any version
            // compatible with any device — the costlier of the two errors, because it only shows
            // up when an installation fails.
            assertThat(full.mapNotNull { it.minSdk }).hasSize(full.size)
            assertThat(full.first().minSdk).isEqualTo(LOLLIPOP)
        }

        @Test
        fun `the download page declares the file it will serve and its hash`() {
            val current = downloadParser.parse(Fixtures.html(Fixtures.DOWNLOAD), DOWNLOAD_URL).expect()
            val old = downloadParser.parse(Fixtures.html(Fixtures.DOWNLOAD_OLD), DOWNLOAD_OLD_URL).expect()

            assertThat(current.fileId).isEqualTo(Fixtures.CURRENT_FILE_ID)
            assertThat(current.info.sha256).isEqualTo(Sha256.parseOrNull(Fixtures.CURRENT_SHA256))
            assertThat(old.fileId).isEqualTo(Fixtures.OLD_VERSION_ID)
            assertThat(old.info.sha256).isEqualTo(Sha256.parseOrNull(Fixtures.OLD_SHA256))
            // Only this page publishes the ABIs, not the listing.
            assertThat(current.info.abis).containsExactlyElementsIn(ABIS)
        }

        @Test
        fun `the Turnstile is still there, and it is what keeps this store user-assisted`() {
            val current = downloadParser.parse(Fixtures.html(Fixtures.DOWNLOAD), DOWNLOAD_URL).expect()

            // It changes nothing in production — the button is a `<button>` and not a link either
            // way, so the download would stay assisted even without the widget. The canary reads
            // it: the day this becomes `false`, uptodown's classification should be re-evaluated
            // rather than left as it is out of inertia.
            assertThat(current.gatedByChallenge).isTrue()
        }

        @Test
        fun `a page with no button produces no download`() {
            val result = downloadParser.parse(Fixtures.html(Fixtures.NOT_FOUND), DOWNLOAD_URL)
            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        }
    }

    @Nested
    @DisplayName("Refs")
    inner class Refs {

        @Test
        fun `the slug is read from the subdomain and only for android pages`() {
            assertThat(refs.refFromUrl("https://telegram.en.uptodown.com/android"))
                .isEqualTo(StoreAppRef("telegram"))
            // Search lives on the language root: without the path constraint it would become an
            // app called "en".
            assertThat(refs.refFromUrl("https://en.uptodown.com/android/search?query=x")).isNull()
            // The other platforms are not ours.
            assertThat(refs.refFromUrl("https://telegram-for-desktop.en.uptodown.com/windows")).isNull()
            // The Spanish of `www` is not the language we serve.
            assertThat(refs.refFromUrl("https://telegram.uptodown.com/android")).isNull()
        }

        @Test
        fun `a ref with a dot does not become an extra subdomain`() {
            // Here the slug ends up inside a **hostname**: `evil.example.com` would produce a
            // request to `evil.example.com.en.uptodown.com`, and with a domain registered for the
            // purpose, somewhere else entirely.
            assertThat(refs.slugOf(StoreAppRef("evil.example.com"))).isNull()
            assertThat(refs.slugOf(StoreAppRef("../../etc/passwd"))).isNull()
            assertThat(refs.slugOf(StoreAppRef(Fixtures.APP_SLUG))).isEqualTo(Fixtures.APP_SLUG)
        }
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixture, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixture, gave Unsupported")
    }

    private companion object {
        const val SEARCH_URL = "https://en.uptodown.com/android/search?query=telegram"
        const val DETAIL_URL = "https://telegram.en.uptodown.com/android"
        const val VERSIONS_URL = "https://telegram.en.uptodown.com/android/versions"
        const val DOWNLOAD_URL = "https://telegram.en.uptodown.com/android/download"
        const val DOWNLOAD_OLD_URL = "https://telegram.en.uptodown.com/android/download/1191373665"
        const val DEVELOPER = "Telegram Messenger LLP"
        const val LICENSE = "GPL 2.0"
        const val LARGE_SUFFIX = ":800"
        const val SEARCH_RESULTS = 36
        const val VERSIONS = 20
        const val EMBEDDED_VERSIONS = 6
        const val SCREENSHOTS = 12
        const val LOLLIPOP = 21
        const val RATING = 4.3f
        const val RATING_COUNT = 4143
        val ABIS = listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64")
    }
}
