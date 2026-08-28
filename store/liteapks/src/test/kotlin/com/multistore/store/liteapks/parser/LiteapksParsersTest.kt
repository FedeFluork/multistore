package com.multistore.store.liteapks.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.liteapks.Fixtures
import com.multistore.store.liteapks.LiteapksConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * liteapks's parsers, against the real pages.
 *
 * Three of this store's defences are invisible to the contract test, and they are what this file
 * exists to prove: the Google Play link that is almost always an advert, the version that lives in
 * two different places depending on the page, and the empty search page that has no container to
 * find empty.
 */
@DisplayName("Parsers — liteapks")
class LiteapksParsersTest {

    private val config = LiteapksConfig(baseUrl = BASE)
    private val search = LiteapksSearchParser(config)
    private val detail = LiteapksDetailParser(config)
    private val download = LiteapksDownloadParser(config)

    @Nested
    @DisplayName("search")
    inner class Search {

        @Test
        @DisplayName("reads the cards, with version, rating and MOD traits")
        fun readsCards() {
            val page = search.parse(Fixtures.html(Fixtures.SEARCH), "$BASE/?s=telegram", 0).expect()

            assertThat(page.items).hasSize(Fixtures.QUERY_RESULTS)
            assertThat(page.totalCount).isEqualTo(Fixtures.QUERY_RESULTS)

            val first = page.items.first()
            assertThat(first.ref).isEqualTo(StoreAppRef(Fixtures.APP_REF))
            // The title comes from the `h2`, not from the `article`'s `aria-label`, which says
            // "Telegram MOD APK" — i.e. the name plus two words of positioning.
            assertThat(first.title).isEqualTo(Fixtures.APP_TITLE)
            assertThat(first.latestVersionName).isEqualTo(Fixtures.APP_VERSION)
            assertThat(first.rating).isNotNull()
            assertThat(first.iconUrl).isNotEmpty()
            // The MOD traits are the only description the card carries.
            assertThat(first.summary.resolve(EN)).isEqualTo("Premium, Lite, No ADS")
            // No `packageName` in the card, ever: it is why the capability is declared `false`
            // despite being on the listing 26 times out of 31.
            assertThat(page.items.map { it.packageName }).containsExactlyElementsIn(
                List(Fixtures.QUERY_RESULTS) { null },
            )
        }

        /**
         * **Zero results is not an empty container, and it is this store's dangerous case.**
         *
         * `div#apps-grid` does not exist on the empty page, and there is no `<article>`: as seen by
         * the parser it is identical to a page where the row selector is dead.
         */
        @Test
        @DisplayName("an empty search is an empty success, with no container to find")
        fun emptySearchHasNoContainerAtAll() {
            val html = Fixtures.html(Fixtures.SEARCH_EMPTY)
            // The page's shape, before the outcome: it is what makes the test a proof and not a
            // caption.
            val document = HtmlPage.of(html, BASE)
            assertThat(document.has("div#apps-grid")).isFalse()
            assertThat(document.all("article")).isEmpty()

            val page = search.parse(html, "$BASE/?s=zzqxwvnbtklmj", 0).expect()

            assertThat(page.items).isEmpty()
            assertThat(page.hasMore).isFalse()
            // No count in parentheses: the heading says "Search Results" and nothing else.
            assertThat(page.totalCount).isNull()
        }

        /**
         * **The real defence: a page that is not a search fails instead of saying "nothing".**
         *
         * Without the check on `h1#search-title`, a listing — or the 404 page, or the home — would
         * give zero rows and pass for a search with no results. On an aggregator that means a store
         * disappearing from the results with nothing saying so: it is the same silent fault
         * `mapRowsOrFail` catches one level down, and here `mapRowsOrFail` cannot see it because the
         * rows are zero in both cases.
         */
        @Test
        @DisplayName("a page that is not a search gives ParseFailure, not zero results")
        fun aPageThatIsNotASearchFails() {
            val result = search.parse(Fixtures.html(Fixtures.APP), "$BASE/telegram.html", 0)

            val error = (result as StoreResult.Failure).error as StoreError.ParseFailure
            assertThat(error.selector).isEqualTo(config.selectors.searchTitle)
        }

        @Test
        @DisplayName("the last page does not promise there is another")
        fun theLastPageStops() {
            val last = search.parse(Fixtures.html(Fixtures.SEARCH_LAST_PAGE), "$BASE/?s=game&paged=4", 3)
                .expect()

            assertThat(last.items).hasSize(Fixtures.LAST_PAGE_ROWS)
            assertThat(last.hasMore).isFalse()
        }
    }

    @Nested
    @DisplayName("the listing")
    inner class Detail {

        @Test
        @DisplayName("reads the game: package, screenshots, category and date")
        fun readsGame() {
            val parsed = detail
                .parse(Fixtures.html(Fixtures.GAME), "$BASE/minecraft.html", StoreAppRef(Fixtures.GAME_REF))
                .expect()

            val summary = parsed.detail.summary
            assertThat(summary.title).isEqualTo(Fixtures.GAME_TITLE)
            assertThat(summary.developer).isEqualTo(Fixtures.GAME_DEVELOPER)
            assertThat(summary.packageName).isEqualTo(Fixtures.GAME_PACKAGE)
            assertThat(summary.categories).containsExactly(Fixtures.GAME_CATEGORY)
            assertThat(summary.contentKind).isEqualTo(ContentKind.GAME)
            assertThat(summary.latestVersionName).isEqualTo(Fixtures.GAME_VERSION)
            assertThat(summary.rating).isWithin(TOLERANCE).of(Fixtures.GAME_RATING)
            assertThat(summary.ratingCount).isEqualTo(Fixtures.GAME_RATING_COUNT)
            assertThat(summary.lastUpdated).isNotNull()
            assertThat(parsed.detail.screenshots).hasSize(Fixtures.GAME_SCREENSHOTS)
            assertThat(parsed.detail.description.resolve(EN)).isNotEmpty()
            // The file page's stem is not derivable from the slug, and this is where it is
            // discovered.
            assertThat(parsed.downloadStem).isEqualTo(Fixtures.GAME_STEM)
        }

        @Test
        @DisplayName("reads the app: no package, no screenshots, the MOD traits")
        fun readsApp() {
            val parsed = detail
                .parse(Fixtures.html(Fixtures.APP), "$BASE/telegram.html", StoreAppRef(Fixtures.APP_REF))
                .expect()

            val summary = parsed.detail.summary
            assertThat(summary.title).isEqualTo(Fixtures.APP_TITLE)
            assertThat(summary.contentKind).isEqualTo(ContentKind.APP)
            assertThat(summary.categories).containsExactly(Fixtures.APP_CATEGORY)
            assertThat(summary.summary.resolve(EN)).isEqualTo(Fixtures.APP_MOD_TRAITS)
            assertThat(parsed.detail.screenshots).isEmpty()
            assertThat(parsed.downloadStem).isEqualTo(Fixtures.APP_STEM)
        }

        /**
         * **The obvious fallback really does come out, and this test shows it positively.**
         *
         * A test that passes both with and without the defence it is meant to prove is not a test,
         * it is a caption. Checking only that the package is `null` would prove nothing — it would
         * be `null` on a page with no Play link at all too.
         *
         * Here the page **does have** a `play.google.com`, and it is the `io.apkmody.sai` advert
         * that sits on 31 listings out of 31. The test shows the two outcomes side by side: outside
         * the container there is the advert, inside there is nothing, and the honest answer is "we
         * do not know".
         */
        @Test
        @DisplayName("where the package is absent, the first Play link is the advert")
        fun theAdvertIsNotThePackage() {
            val html = Fixtures.html(Fixtures.APP)
            val document = HtmlPage.of(html, "$BASE/telegram.html")

            val naive = document.absUrl("a[href*=play.google.com]", "href")
            assertThat(naive).contains(Fixtures.PLAY_ADVERT_PACKAGE)
            assertThat(document.all(config.selectors.detailPlayLink)).isEmpty()

            val parsed = detail.parse(html, "$BASE/telegram.html", StoreAppRef(Fixtures.APP_REF)).expect()
            assertThat(parsed.detail.summary.packageName).isNull()
        }

        /**
         * **The package is read as a query parameter, not by cutting after `id=`.**
         *
         * Five listings out of thirty-one write `?id=org.telegram.plus&hl=en&gl=US`. A naive cut
         * would give `org.telegram.plus&hl=en&gl=US`, which would never match the APK's package:
         * step 4 of the pre-install pipeline would **block** every installation of those apps, at
         * the last metre and without saying why.
         *
         * Here too the proof is positive: it is shown that on the page the raw value really is
         * dirty.
         */
        @Test
        @DisplayName("the package stops at the first &, even when Play carries hl and gl")
        fun playLinkKeepsOnlyTheId() {
            val html = Fixtures.html(Fixtures.PLAY_PARAMS)
            val href = HtmlPage.of(html, BASE).absUrl(config.selectors.detailPlayLink, "href")
            // The raw form: without this line the test would pass on a clean page too.
            assertThat(href.substringAfter("id=")).isEqualTo("org.telegram.plus&hl=en&gl=US")

            val parsed = detail.parse(html, BASE, StoreAppRef("plus-messenger-2")).expect()
            assertThat(parsed.detail.summary.packageName).isEqualTo("org.telegram.plus")
        }
    }

    @Nested
    @DisplayName("the file page")
    inner class Files {

        /**
         * Where the row carries the version, it is read from the row.
         *
         * This page's three headings are not versions but group names — "Minecraft - Official
         * Versions", "Minecraft - Beta Versions", "Minecraft - Full/Paid" — so without reading the
         * row these six files would be left **without a version**, i.e. discarded.
         *
         * What this test does **not** prove is the order between the two sources: across 66 real
         * rows it never happens that both carry a version, so swapping them changes nothing. See the
         * note on `versionOf` in the parser.
         */
        @Test
        @DisplayName("where the row carries the version, it is read from the row")
        fun versionComesFromTheRow() {
            val files = download.parseFiles(Fixtures.html(Fixtures.DOWNLOAD_GAME), BASE).expect()

            assertThat(files).hasSize(Fixtures.GAME_FILES)
            assertThat(files.first().versionName).isEqualTo(Fixtures.GAME_FIRST_VERSION)
            assertThat(files.map { it.versionName }).containsNoDuplicates()
            assertThat(files.map { it.isOriginal }).doesNotContain(true)
            // The heading's shape, which is what makes this test a proof: if the parser read that
            // one, three files would have the same name.
            val headers = HtmlPage.of(Fixtures.html(Fixtures.DOWNLOAD_GAME), BASE)
                .all("button.dl-version-tab")
                .mapNotNull { it.textOrNull() }
            assertThat(headers).hasSize(3)
            headers.forEach { assertThat(it).doesNotContain("v1.") }
        }

        /**
         * Where the row does **not** carry the version, we climb to the block — and keep the
         * variant.
         *
         * Telegram's two v12.10.1 files say only `Premium/Web` and `Premium`: without the variant
         * in parentheses they would be two versions with the identical name, i.e. two
         * indistinguishable rows in the history.
         */
        @Test
        @DisplayName("with no version on the row we climb to the group, and the variant stays")
        fun versionComesFromTheGroupHeader() {
            val files = download.parseFiles(Fixtures.html(Fixtures.DOWNLOAD_APP), BASE).expect()

            assertThat(files).hasSize(Fixtures.APP_FILES)
            assertThat(files.first().versionName).isEqualTo(Fixtures.APP_FIRST_VERSION)
            assertThat(files.map { it.versionName }).containsNoDuplicates()

            val rows = HtmlPage.of(Fixtures.html(Fixtures.DOWNLOAD_APP), BASE)
                .all(config.selectors.downloadItem)
                .mapNotNull { it.textOrNull(config.selectors.downloadItemLabel) }
            assertThat(rows.first()).isEqualTo("Premium/Web")
        }

        /**
         * **A page with a single version has no `div#dl-versions`, and they are 14 out of 31.**
         *
         * Anchoring the row selector to the container would lose them all, silently: those listings
         * would say "nothing to download" while having a file.
         */
        @Test
        @DisplayName("the single-version page has no container, and the files are read all the same")
        fun theSingleVersionPageHasNoContainer() {
            val html = Fixtures.html(Fixtures.DOWNLOAD_SINGLE)
            assertThat(HtmlPage.of(html, BASE).has("div#dl-versions")).isFalse()
            assertThat(HtmlPage.of(html, BASE).all("button.dl-version-tab")).isEmpty()

            val files = download.parseFiles(html, BASE).expect()

            assertThat(files).hasSize(1)
            assertThat(files.single().versionName).startsWith("6.6.2")
        }

        /**
         * The **original** files are recognised by host, carry their own type, and one is dead.
         *
         * They are the unmodified APK the site hosts next to its own. They have no intermediate
         * page — the ref *is* the URL — and they are often `.xapk`: declaring them `APK` would send
         * them to `PackageInstaller` as though they were a single file.
         */
        @Test
        @DisplayName("the originals are direct URLs, with the type read from the name")
        fun originalFilesAreDirect() {
            val files = download.parseFiles(Fixtures.html(Fixtures.DOWNLOAD_ORIGINAL), BASE).expect()

            val (originals, own) = files.partition { it.isOriginal }
            assertThat(own).hasSize(2)
            assertThat(originals).hasSize(2)
            originals.forEach { assertThat(it.ref.value).startsWith("https://") }
            assertThat(originals.map { it.artifactType })
                .containsExactly(ArtifactType.XAPK, ArtifactType.APK)
            // The originals' version has no leading `v`: `3.0.20 Original`. Without that tolerance
            // these two rows would be discarded — and on `minecraft-earth` they are the only file
            // that exists.
            originals.forEach { assertThat(it.versionName).endsWith("Original") }
        }

        /**
         * The size is read even when the `B` is missing, which is how the originals write it.
         *
         * `40.05 MB` on the store's rows, `71M` on the original ones. Eleven rows out of sixty-six
         * use the second form.
         */
        @Test
        @DisplayName("the size reads both as `40.05 MB` and as `71M`")
        fun sizeIsReadInBothForms() {
            val files = download.parseFiles(Fixtures.html(Fixtures.DOWNLOAD_ORIGINAL), BASE).expect()

            files.forEach { assertThat(it.sizeBytes).isNotNull() }
            val raw = HtmlPage.of(Fixtures.html(Fixtures.DOWNLOAD_ORIGINAL), BASE)
                .all(config.selectors.downloadItem)
                .mapNotNull { it.textOrNull(config.selectors.downloadItemSize) }
            // The form without `B` really is on the page: without this line the test would pass
            // even if the tolerance had been removed from `TextValues`.
            assertThat(raw.any { it.endsWith("M") }).isTrue()
        }

        /**
         * **An already-encoded `data-link` is not re-encoded, `%2B` included.**
         *
         * This test was born from the injection, not before: the fixture initially chosen for this
         * case — Minecraft's slot — carries a URL **with no escapes at all**, so removing the
         * condition from the normalisation left it green. It was a caption.
         *
         * Here the URL has three kinds: `%20`, `%28`/`%29` and above all `%2B`. A second pass would
         * turn them into `%2520` and `%252B`, and the worker would answer **404 `NoSuchKey`** — the
         * storage key does not exist, while the file does.
         */
        @Test
        @DisplayName("the already-encoded data-link is not re-encoded, %2B included")
        fun encodedSlotLinkIsLeftAlone() {
            val url = download.parseSlotLink(Fixtures.html(Fixtures.SLOT_ENCODED), BASE).expect()

            assertThat(url).isEqualTo(Fixtures.ENCODED_FILE_URL)
            assertThat(url).doesNotContain("%2520")
            assertThat(url).doesNotContain("%252B")
        }

        /** A URL with no spaces and no escapes comes out identical: it is the normal path. */
        @Test
        @DisplayName("a data-link with nothing to normalise comes out identical")
        fun plainSlotLinkIsUnchanged() {
            val url = download.parseSlotLink(Fixtures.html(Fixtures.SLOT), BASE).expect()

            assertThat(url).isEqualTo(Fixtures.GAME_FILE_URL)
        }

        @Test
        @DisplayName("the data-link with raw spaces is encoded exactly once")
        fun rawSlotLinkIsEncodedOnce() {
            val url = download.parseSlotLink(Fixtures.html(Fixtures.SLOT_RAW_SPACES), BASE).expect()

            assertThat(url).isEqualTo(Fixtures.APP_FILE_URL)
            assertThat(url).doesNotContain(" ")
            assertThat(url).doesNotContain("%2520")
        }
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixture, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixture, gave Unsupported")
    }

    private companion object {
        const val BASE = "https://liteapks.com"
        val EN = listOf("en")
        const val TOLERANCE = 0.01f
    }
}
