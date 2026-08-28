package com.multistore.store.pdalife.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.pdalife.Fixtures
import com.multistore.store.pdalife.PdalifeConfig
import com.multistore.store.pdalife.PdalifeRefs
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * pdalife's parsers against the real pages.
 *
 * A test that passes both with and without the defence it is meant to prove is not a test, it is a
 * caption. On this store there are four defences to prove, and each has a test written **positively**
 * here — one that shows what the wrong fallback produces, not only that the right thing comes out:
 *
 *  1. the container and the double class of the search row ([SearchTest]);
 *  2. the operating-system filter, which lives in the selector ([SearchTest]);
 *  3. the offers container, without which the `packageName` is an advert ([DetailTest]);
 *  4. the anchoring to `data-version_id`, which discards the advert dressed as a button
 *     ([VersionsTest]).
 */
@DisplayName("Parsers — pdalife")
class PdalifeParsersTest {

    private val config = PdalifeConfig(baseUrl = BASE_URL)
    private val searchParser = PdalifeSearchParser(config)
    private val detailParser = PdalifeDetailParser(config)

    @Nested
    @DisplayName("search")
    inner class SearchTest {

        @Test
        @DisplayName("reads the real page's results, with rating, version and category")
        fun readsResults() {
            val page = search(Fixtures.SEARCH, 0)
            assertThat(page.items).hasSize(Fixtures.PAGE_1_ANDROID)

            val first = page.items.first()
            assertThat(first.title).isEqualTo("Minecraft - Pocket Edition APK mod full")
            assertThat(first.ref.value).isEqualTo("minecraft-pocket-edition-android-a1552")
            assertThat(first.latestVersionName).isEqualTo("1.26.50.26")
            assertThat(first.categories).containsExactly("Simulators")
            assertThat(first.iconUrl).isEqualTo(
                "https://pdalife.com/app/59522ace02abb/m_minecraft-play-with-friends.png",
            )
            // 8 out of 10 becomes 4 out of 5: the store's scale is not the app's.
            assertThat(first.rating).isWithin(TOLERANCE).of(4f)
            assertThat(first.summary.byTag.values.single()).startsWith("The most popular 8-bit")
            // The `packageName` is **never** among the results: it is only on the listing.
            assertThat(page.items.mapNotNull { it.packageName }).isEmpty()
        }

        @Test
        @DisplayName("iOS and PSP do not enter the results")
        fun otherPlatformsAreFilteredOut() {
            val document = HtmlPage.of(Fixtures.html(Fixtures.SEARCH), BASE_URL)
            // The page really does contain the other platforms' rows: two out of twenty.
            val everyRow = document.all("ul.catalog-list > li.catalog-item.js-list-item")
            assertThat(everyRow).hasSize(Fixtures.PAGE_1_ROWS)

            val page = search(Fixtures.SEARCH, 0)
            assertThat(page.items).hasSize(Fixtures.PAGE_1_ANDROID)
            assertThat(page.items.map { it.ref.value }).doesNotContain("minecraft-pocket-edition1-ios-a8721")
            page.items.forEach { assertThat(it.ref.value).contains("-android-a") }
        }

        /**
         * **The defence the selector filter exists to carry, and which the other fixtures did not
         * prove.**
         *
         * With Android *and* iOS rows mixed, removing `:has(a.color-android)` from the selector
         * changes nothing: the iOS rows come in, `PdalifeRefs.refFromUrl` rejects them because the
         * ref requires `-android-`, and the count stays the same. Verified by injection: green.
         *
         * Everything changes when the Android rows are **zero**. `/search/procreate/` — iPad
         * brushes — returns twenty results and none is Android. Without the filter,
         * `mapRowsOrFail` would find twenty rows and be unable to read any, i.e. would say
         * `ParseFailure`: "this store broke" instead of "there is nothing for Android". With the
         * filter, those rows are not even selected.
         */
        @Test
        @DisplayName("a page of iOS-only results is empty, not a ParseFailure")
        fun aPageWithoutAndroidRowsIsEmptyNotBroken() {
            val document = HtmlPage.of(Fixtures.html(Fixtures.SEARCH_OTHER_OS), BASE_URL)
            val everyRow = document.all("ul.catalog-list > li.catalog-item.js-list-item")
            assertThat(everyRow).hasSize(Fixtures.OTHER_OS_ROWS)
            everyRow.forEach {
                assertThat(it.attrOrNull("p.catalog-item__title a", "class")).doesNotContain("android")
            }

            val result = searchParser.parse(Fixtures.html(Fixtures.SEARCH_OTHER_OS), BASE_URL, 0)
            assertThat(result).isInstanceOf(StoreResult.Success::class.java)
            assertThat((result as StoreResult.Success).value.items).isEmpty()
        }

        /**
         * The rating block is on every row, and on apps nobody has voted for it says `0`.
         *
         * The scale the listing declares starts at one (`worstRating = 1`), so zero is not a
         * judgement: it is the absence of judgements. Reporting it as `0.0` would tell the user
         * that app has been rated terrible. None of the other search fixtures contains a zero row —
         * the minimum is 1 — so without this one the distinction was unproven.
         */
        @Test
        @DisplayName("a zero rating is 'no rating', not 'rated terrible'")
        fun zeroRatingIsNoRating() {
            val document = HtmlPage.of(Fixtures.html(Fixtures.SEARCH_UNRATED), BASE_URL)
            assertThat(document.textOrNull(".catalog-item__rating .rating-circle")).isEqualTo("0")

            val page = search(Fixtures.SEARCH_UNRATED, 0)
            val only = page.items.single()
            assertThat(only.ref.value).isEqualTo(Fixtures.UNRATED_REF)
            assertThat(only.rating).isNull()
        }

        @Test
        @DisplayName("the second page does not repeat the first, and declares it is the last")
        fun paginates() {
            val first = search(Fixtures.SEARCH, 0)
            val second = search(Fixtures.SEARCH_PAGE_2, 1)

            assertThat(second.items).hasSize(Fixtures.PAGE_2_ANDROID)
            assertThat(first.items.map { it.ref }).containsNoneIn(second.items.map { it.ref })
            // `data-max_page="2"`, `data-current_page="1"` -> there is more; on the second there is not.
            assertThat(first.hasMore).isTrue()
            assertThat(second.hasMore).isFalse()
        }

        /**
         * **The defence easiest to remove without noticing.**
         *
         * The page with no results is not free of rows: it contains
         * `<li class="catalog-item">Oops, maybe try another request?</li>`, i.e. a row with the
         * first class and nothing inside. With `li.catalog-item` in place of
         * `li.catalog-item.js-list-item`, `mapRowsOrFail` would see "one row, none readable" and
         * would answer `ParseFailure` — on **every** empty search, opening the breaker.
         *
         * The test proves it positively: first it shows the row is there, then that the outcome is
         * zero results and not an error.
         */
        @Test
        @DisplayName("'no results' stays zero results, and does not become a ParseFailure")
        fun emptySearchIsNotAParseFailure() {
            val document = HtmlPage.of(Fixtures.html(Fixtures.SEARCH_EMPTY), BASE_URL)
            val looseRows = document.all("ul.catalog-list > li.catalog-item")
            assertThat(looseRows).hasSize(1)
            assertThat(looseRows.single().textOrNull()).contains("another request")

            val page = search(Fixtures.SEARCH_EMPTY, 0)
            assertThat(page.items).isEmpty()
            assertThat(page.hasMore).isFalse()
        }

        /**
         * The sidebar publishes ten listings with the **same** `a.color-android` as the results,
         * and publishes them even when the results are zero. The container excludes them.
         */
        @Test
        @DisplayName("the sidebar does not become a result even on an empty search")
        fun sidebarIsNotAResult() {
            val document = HtmlPage.of(Fixtures.html(Fixtures.SEARCH_EMPTY), BASE_URL)
            val sidebar = document.all("a.side-top__link.color-android")
            assertThat(sidebar.size).isAtLeast(SIDEBAR_MINIMUM)

            assertThat(search(Fixtures.SEARCH_EMPTY, 0).items).isEmpty()
        }

        private fun search(fixture: String, page: Int) =
            searchParser.parse(Fixtures.html(fixture), BASE_URL, page).expectSuccess()
    }

    @Nested
    @DisplayName("listing")
    inner class DetailTest {

        @Test
        @DisplayName("reads a program's listing with its real package")
        fun readsApp() {
            val detail = detail(Fixtures.DETAIL, Fixtures.APP_REF)
            val summary = detail.summary

            assertThat(summary.title).isEqualTo(Fixtures.APP_TITLE)
            assertThat(summary.developer).isEqualTo(Fixtures.APP_DEVELOPER)
            assertThat(summary.packageName).isEqualTo(Fixtures.APP_PACKAGE)
            assertThat(summary.categories).containsExactly(Fixtures.APP_CATEGORY)
            // `/android/programmy/` in the breadcrumbs: a program, not a game.
            assertThat(summary.contentKind).isEqualTo(ContentKind.APP)
            assertThat(summary.latestVersionName).isEqualTo(Fixtures.APP_VERSION)
            assertThat(summary.latestVersionCode).isNull()
            assertThat(summary.ratingCount).isEqualTo(Fixtures.APP_RATING_COUNT)
            // 9.2292 out of 10 rescaled to 5.
            assertThat(summary.rating).isWithin(TOLERANCE)
                .of(Fixtures.APP_RATING_OUT_OF_TEN / 2f)
            assertThat(detail.screenshots).hasSize(Fixtures.APP_SCREENSHOTS)
            // The anchor's `href`, not the thumbnail's `src`.
            assertThat(detail.screenshots.first().url).endsWith("/m_img1.jpg")
            assertThat(detail.description.byTag.values.single()).contains("Pavel Durov")
        }

        @Test
        @DisplayName("a modified game: game category, and 'Money Mod' stays in the name")
        fun readsModdedGame() {
            val detail = detail(Fixtures.DETAIL_MOD, Fixtures.MOD_REF)

            assertThat(detail.summary.title).isEqualTo(Fixtures.MOD_TITLE)
            assertThat(detail.summary.contentKind).isEqualTo(ContentKind.GAME)
            assertThat(detail.summary.packageName).isEqualTo(Fixtures.MOD_PACKAGE)
            assertThat(detail.summary.latestVersionName).isEqualTo(Fixtures.MOD_VERSION)
        }

        /**
         * **The costliest defence to get wrong on this store.**
         *
         * Telegram's listing has five links to Google Play: four are the advert
         * `cc.peacedeath.peacedeathapp`, one is the real one — and it is the **last**. The test
         * proves it positively: it shows the generic selector returns the advert, and that the one
         * anchored to the offers container does not.
         *
         * It is not an isolated case: across 17 sampled listings the naive read gives that package
         * 17 times out of 17.
         */
        @Test
        @DisplayName("the package is not the first Play link, which on every listing is an advert")
        fun doesNotFallBackToTheFirstPlayLink() {
            val document = HtmlPage.of(Fixtures.html(Fixtures.DETAIL), BASE_URL)
            val naive = document.all("a[href*=play.google.com]")
            assertThat(naive.size).isAtLeast(2)
            assertThat(naive.first().ownAttrOrNull("href"))
                .contains(Fixtures.PLAY_ADVERT_PACKAGE)

            assertThat(detail(Fixtures.DETAIL, Fixtures.APP_REF).summary.packageName)
                .isEqualTo(Fixtures.APP_PACKAGE)
        }

        /**
         * Five listings in seventeen have no offers container, because the app is not on Play. The
         * outcome is `null`, **not** the advert — which on this page is very much present.
         */
        @Test
        @DisplayName("without the offers container the package is absent, not invented")
        fun missingOffersMeansNoPackage() {
            val document = HtmlPage.of(Fixtures.html(Fixtures.DETAIL_NO_PACKAGE), BASE_URL)
            assertThat(document.oneOrNull(".game-download__stores")).isNull()
            assertThat(document.all("a[href*=play.google.com]")).isNotEmpty()

            val detail = detail(Fixtures.DETAIL_NO_PACKAGE, Fixtures.NO_PACKAGE_REF)
            assertThat(detail.summary.title).isEqualTo(Fixtures.NO_PACKAGE_TITLE)
            assertThat(detail.summary.packageName).isNull()
        }

        /**
         * `OS version: Android 2.2+` sits in a `ul.game-download__list`; next to it is a second
         * one, "Help", with the same markup. Positional selectors are forbidden here, so the row is
         * recognised by the **shape** of its content.
         */
        @Test
        @DisplayName("the minimum SDK is recognised by shape, not by position in the list")
        fun readsMinSdkWithoutCountingLists() {
            val document = HtmlPage.of(Fixtures.html(Fixtures.DETAIL), BASE_URL)
            assertThat(document.all("ul.game-download__list").size).isAtLeast(2)

            // Android 2.2 = API 8. The value is there and not null: "I did not read it" and "I
            // read it and it is very low" are two different answers.
            detail(Fixtures.DETAIL, Fixtures.APP_REF).versions.forEach {
                assertThat(it.minSdk).isEqualTo(ANDROID_2_2)
            }
        }

        private fun detail(fixture: String, ref: String) =
            detailParser.parse(Fixtures.html(fixture), BASE_URL, StoreAppRef(ref)).expectSuccess()
    }

    @Nested
    @DisplayName("versions")
    inner class VersionsTest {

        @Test
        @DisplayName("four versions, newest first, with date and size")
        fun readsVersions() {
            val versions = detail(Fixtures.DETAIL).versions
            assertThat(versions).hasSize(Fixtures.APP_VERSIONS)

            val newest = versions.first()
            assertThat(newest.versionName).isEqualTo(Fixtures.APP_VERSION)
            assertThat(newest.ref.value).isEqualTo(Fixtures.APP_DOWNLOAD_HASH)
            assertThat(newest.sizeBytes).isEqualTo(SIZE_68_83_MB)
            assertThat(newest.publishedAt.toString()).startsWith("2023-07-26")
            // No version code anywhere on the site: see the note on `data-version_id`.
            assertThat(versions.map { it.versionCode }.toSet()).containsExactly(null)

            assertThat(versions[1].ref.value).isEqualTo(Fixtures.APP_OLD_DOWNLOAD_HASH)
            // Ordered by decreasing `data-version_id`: 96571, 93943, 90481, 89705. That this is
            // also chronological order is not guaranteed by the site, and it is what gets checked.
            val dates = versions.map { requireNotNull(it.publishedAt) }
            assertThat(dates).isEqualTo(dates.sortedDescending())
        }

        /**
         * **The advert that calls itself "download buttons" in its own attributes.**
         *
         * Right after every `ul.game-versions__downloads-list` the template puts
         * `<div class="js-banner" data-type="app_download_buttons">`. The test proves it
         * positively: it shows the banner is there, as many times as there are versions, and that
         * the versions read stay four with the right octets.
         */
        @Test
        @DisplayName("advertising banners do not become versions")
        fun advertBannersAreNotVersions() {
            val document = HtmlPage.of(Fixtures.html(Fixtures.DETAIL), BASE_URL)
            val banners = document.all("div.js-banner[data-type=app_download_buttons]")
            assertThat(banners).hasSize(Fixtures.APP_VERSIONS)

            val versions = detail(Fixtures.DETAIL).versions
            assertThat(versions).hasSize(Fixtures.APP_VERSIONS)
            versions.forEach { assertThat(it.ref.value).hasLength(HASH_LENGTH) }
        }

        /**
         * The changelog is **not** always preceded by the hyphen.
         *
         * Three versions out of four write `26.07.2023  - Changes not specified.`; the fourth
         * writes `11.11.2022  Topics in groups and more`. A cut at the first ` - ` would lose
         * exactly the listing's only real note.
         */
        @Test
        @DisplayName("the changelog without a hyphen is not lost")
        fun changelogWithoutSeparatorSurvives() {
            val versions = detail(Fixtures.DETAIL).versions
            val oldest = versions.last()
            assertThat(oldest.changelog.byTag.values.single())
                .startsWith("Topics in groups and more")
            // And the date stays where it is, not in the text.
            assertThat(oldest.changelog.byTag.values.single()).doesNotContain("11.11.2022")
            assertThat(oldest.publishedAt.toString()).startsWith("2022-11-11")
        }

        /** `8.02.2026` next to `25.05.2026`: the day is not always two digits. */
        @Test
        @DisplayName("a date with a single-digit day reads like the others")
        fun singleDigitDayIsParsed() {
            val versions = detail(Fixtures.DETAIL_MOD).versions
            assertThat(versions.map { it.publishedAt.toString().take(DATE_CHARS) })
                .containsExactly("2026-05-25", "2026-02-08")
                .inOrder()
        }

        private fun detail(fixture: String) = detailParser
            .parse(Fixtures.html(fixture), BASE_URL, StoreAppRef(Fixtures.APP_REF))
            .expectSuccess()
    }

    /**
     * The refs, tested **directly**.
     *
     * Going through the parsers this rule is not checkable: the search selector already discards
     * non-Android rows, so an iOS ref never reaches [PdalifeRefs]. Verified by injection — widening
     * [PdalifeRefs] left the suite green — and this class is the answer: the two defences overlap,
     * and each has to be proven where it acts.
     *
     * That the other one is needed too is not theory. The ref comes from the core, which read it
     * from Room: a row written before a selector change, or a ref built from a malformed
     * `parsers.json`, enters `getAppDetails` without going through any search.
     */
    @Nested
    @DisplayName("refs")
    inner class RefsTest {

        @Test
        @DisplayName("an Android listing becomes a ref")
        fun androidUrlBecomesARef() {
            val ref = PdalifeRefs.refFromUrl("https://pdalife.com/telegram-android-a14523.html")
            assertThat(ref?.value).isEqualTo(Fixtures.APP_REF)
            assertThat(PdalifeRefs.idOf(requireNotNull(ref))).isEqualTo(Fixtures.APP_ID)
        }

        /**
         * iOS and PSP are **valid** pdalife URLs, and must not become refs.
         *
         * They are taken from the fixtures: `/telegram1-ios-a26129.html` sits next to Telegram in
         * the results, and `/-psp-a34978.html` — with an **empty** alias — on the second page of
         * "minecraft". A ref to those listings would lead to a page that exists and from which
         * nothing installable on Android can be downloaded.
         */
        @Test
        @DisplayName("iOS and PSP do not become refs")
        fun otherPlatformUrlsAreRejected() {
            listOf(
                "https://pdalife.com/telegram1-ios-a26129.html",
                "https://pdalife.com/minecraft-pocket-edition1-ios-a8721.html",
                "https://pdalife.com/-psp-a34978.html",
            ).forEach { assertThat(PdalifeRefs.refFromUrl(it)).isNull() }
        }

        /** The contract test hands every adapter a malicious ref: here it does not become a URL. */
        @Test
        @DisplayName("a ref that does not have a stem's shape produces nothing")
        fun malformedRefIsRejected() {
            listOf(
                "../../etc/passwd?<script>&%00",
                "telegram-ios-a14523",
                "telegram-android-a",
                "/",
                // The empty ref is not on this list because it does not exist: `StoreAppRef`
                // rejects it in the constructor. One fewer case to defend here.
            ).forEach { assertThat(PdalifeRefs.stem(StoreAppRef(it))).isNull() }
        }

        /** `33n84e18` and `9n420705` are real octets: not hexadecimal, and must be accepted. */
        @Test
        @DisplayName("the download octet is not hexadecimal")
        fun downloadHashIsNotHex() {
            listOf("fe8bc99d", "33n84e18", "9n420705").forEach {
                assertThat(PdalifeRefs.hashFromDownloadUrl("https://pdalife.com/dwn/$it.html?lang=en"))
                    .isEqualTo(it)
            }
            // And what does not have that shape is not one.
            assertThat(PdalifeRefs.hashFromDownloadUrl("https://pdalife.com/dwn/toolong.html"))
                .isNull()
            assertThat(PdalifeRefs.hashFromDownloadUrl("https://pdalife.com/other/fe8bc99d.html"))
                .isNull()
        }
    }

    private fun <T> StoreResult<T>.expectSuccess(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("expected Success, got Failure($error)")
        StoreResult.Unsupported -> error("expected Success, got Unsupported")
    }

    private companion object {
        const val BASE_URL = "https://pdalife.com"
        const val TOLERANCE = 0.001f
        const val SIDEBAR_MINIMUM = 5
        const val ANDROID_2_2 = 8
        const val HASH_LENGTH = 8
        const val DATE_CHARS = 10

        /** `68.83 Mb` in binary units: what `TextValues.byteSize` writes. */
        const val SIZE_68_83_MB = 72_173_486L
    }
}
