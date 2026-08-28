package com.multistore.store.modyolo.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.modyolo.Fixtures
import com.multistore.store.modyolo.ModyoloConfig
import com.multistore.store.modyolo.ModyoloRefs
import com.multistore.store.modyolo.ModyoloSelectors
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * modyolo's parsers against the real responses, field by field.
 *
 * The contract test checks that the adapter **behaves** as it declares; here we check that it reads
 * the right values and that it **fails** when it should. Every defence is proven by removing it: a
 * test that passes both with and without the defence it is meant to prove is not a test, it is a
 * caption.
 */
@DisplayName("Parsers — modyolo")
class ModyoloParsersTest {

    private val config = ModyoloConfig()
    private val searchParser = ModyoloSearchParser(config)
    private val detailParser = ModyoloDetailParser()
    private val downloadParser = ModyoloDownloadParser(config)

    private val downloadUrl = "${ModyoloConfig.DEFAULT_BASE_URL}/download/${Fixtures.APP_REF}/1"

    @Nested
    @DisplayName("search")
    inner class Search {

        @Test
        @DisplayName("reads title, excerpt and icon of every result")
        fun readsEveryField() {
            val page = searchParser
                .parse(Fixtures.text(Fixtures.SEARCH), page = 0, query = "minecraft")
                .expect()

            assertThat(page.items).hasSize(SEARCH_RESULTS)
            val icons = page.items.mapNotNull { it.iconUrl }
            // The icon comes from `_embed=wp:featuredmedia`, which costs 180 KB of JSON for twenty
            // results. If `_links` disappeared from `_fields`, WordPress would stop attaching
            // `_embedded` and every icon would become null — with no error at all.
            assertThat(icons).hasSize(SEARCH_RESULTS)
            icons.forEach { assertThat(it).startsWith("https://") }
        }

        @Test
        @DisplayName("the title's HTML entities do not reach the screen")
        fun titlesAreUnescaped() {
            val page = searchParser
                .parse(Fixtures.text(Fixtures.SEARCH), page = 0, query = "minecraft")
                .expect()

            // WordPress delivers `Video Compressor &#038; Converter`. That title ends up in "My
            // apps" and in update notifications: showing it as-is is a visible defect, but only on
            // apps with an `&` in their name.
            assertThat(page.items.map { it.title }).doesNotContain("")
            page.items.forEach {
                assertThat(it.title).doesNotContain("&#")
                assertThat(it.title).doesNotContain("<")
            }
        }

        @Test
        @DisplayName("whoever has the term in the title comes first")
        fun titleMatchesComeFirst() {
            val page = searchParser
                .parse(Fixtures.text(Fixtures.SEARCH), page = 0, query = "minecraft")
                .expect()

            // WordPress relevance is full-text over title **and body**, and on "telegram" it puts
            // an icon pack second. Without reordering, with nine stores merged into a single list,
            // an off-topic result pushes another store's right one down.
            val withTerm = page.items.takeWhile { it.title.lowercase().contains("minecraft") }
            assertThat(withTerm).isNotEmpty()
            page.items.drop(withTerm.size).forEach {
                assertThat(it.title.lowercase()).doesNotContain("minecraft")
            }
        }

        @Test
        @DisplayName("an empty list is a search without results, not an error")
        fun emptyIsNotAFailure() {
            val page = searchParser
                .parse(Fixtures.text(Fixtures.SEARCH_EMPTY), page = 0, query = "x")
                .expect()

            assertThat(page.items).isEmpty()
        }

        @Test
        @DisplayName("a different JSON schema is a ParseFailure, not zero results")
        fun aChangedSchemaFails() {
            val result = searchParser.parse("""{"code":"rest_no_route"}""", 0, "x")

            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
            assertThat((result as StoreResult.Failure).error)
                .isInstanceOf(StoreError.ParseFailure::class.java)
        }

        @Test
        @DisplayName("results that are there and none readable is a ParseFailure")
        fun unreadableRowsFail() {
            // Same distinction `mapRowsOrFail` makes for HTML, applied to JSON: twenty objects
            // arriving and none with a usable `id` and `slug` means the schema changed, not that
            // the catalogue is empty.
            val result = searchParser.parse("""[{"slug":"","id":0},{"slug":"","id":0}]""", 0, "x")

            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        }
    }

    @Nested
    @DisplayName("listing")
    inner class Detail {

        @Test
        @DisplayName("reads the listing and deduces the package from it")
        fun readsTheListing() {
            val detail = detailParser
                .parse(Fixtures.text(Fixtures.DETAIL), StoreAppRef(Fixtures.APP_REF))
                .expect()

            assertThat(detail.summary.title).isEqualTo(Fixtures.APP_TITLE)
            assertThat(detail.summary.developer).isEqualTo(Fixtures.APP_PUBLISHER)
            assertThat(detail.summary.latestVersionName).isEqualTo(Fixtures.APP_VERSION)
            assertThat(detail.summary.packageName).isEqualTo(Fixtures.APP_PACKAGE)
            // `lastest_version` with the typo: that is what the field is called in their schema,
            // and reading it as `latest_version` would give `null` with no error.
            assertThat(detail.summary.latestVersionCode).isNull()
        }

        @Test
        @DisplayName("the MOD notes come from the tab, however it is spelled")
        fun modNotesComeFromTheTab() {
            val detail = detailParser
                .parse(Fixtures.text(Fixtures.DETAIL), StoreAppRef(Fixtures.APP_REF))
                .expect()

            // Five spellings are observed (`MOD Info`, `MOD Info?`, `MOD INFO?`, `Mod Info?`,
            // absent): an exact match would lose the tab on 25% of posts.
            assertThat(detail.whatsNew.byTag).isNotEmpty()
        }

        @Test
        @DisplayName("a link that is not Google Play produces no package")
        fun aNonPlayLinkYieldsNoPackage() {
            // Visual novels distributed via Patreon have an `original_download_url` pointing at
            // `patreon.com`. Taking its last segment would give something that *looks* like a
            // package and is not — and the hard block at step 4 of the pre-install pipeline would
            // make that app uninstallable forever.
            // The slashes in the JSON arrive escaped (`https:\/\/play.google.com\/…`): that is
            // how WordPress serialises, and substituting the readable form would find nothing —
            // the test would pass without having changed the fixture.
            val original = "https:\\/\\/play.google.com\\/store\\/apps\\/details?id=${Fixtures.APP_PACKAGE}"
            val text = Fixtures.text(Fixtures.DETAIL)
            assertThat(text).contains(original)
            val patched = text.replace(original, "https:\\/\\/www.patreon.com\\/cw\\/hilummi")
            val detail = detailParser.parse(patched, StoreAppRef(Fixtures.APP_REF)).expect()

            assertThat(detail.summary.packageName).isNull()
        }

        @Test
        @DisplayName("HTTP 200 with `data: null` is NotFound, not an empty listing")
        fun nullDataIsNotFound() {
            val result = detailParser
                .parse(Fixtures.text(Fixtures.DETAIL_MISSING), StoreAppRef("x-1"))

            // That is how modyolo says "this post does not exist", and the HTTP code is 200. An
            // adapter trusting the code would show a nameless listing.
            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
            assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
        }
    }

    @Nested
    @DisplayName("download")
    inner class Download {

        @Test
        @DisplayName("reads the file from the AJAX fragment")
        fun readsTheFile() {
            val file = downloadParser
                .parseFile(Fixtures.text(Fixtures.DOWNLOAD_AJAX), downloadUrl)
                .expect()

            assertThat(file.fileName).isEqualTo(Fixtures.APP_FILE)
            assertThat(file.declaredSize).isGreaterThan(0L)
        }

        @Test
        @DisplayName("reads the three variants with their indices")
        fun readsTheVariants() {
            val versions = downloadParser
                .parseVersions(Fixtures.text(Fixtures.DOWNLOAD_PAGE), downloadUrl)
                .expect()

            assertThat(versions).hasSize(Fixtures.APP_VARIANTS)
            assertThat(versions.map { it.ref.value }).containsExactly("1", "2", "3").inOrder()
            // The leading `v` is the store's typography; "MOD" next to the number is **not**: it
            // tells the user that variant is not the original build.
            assertThat(versions.first().versionName).isEqualTo("${Fixtures.APP_VERSION} MOD")
        }

        @Test
        @DisplayName("the variants' size comes from the panel, not the heading")
        fun sizesComeFromThePanel() {
            val versions = downloadParser
                .parseVersions(Fixtures.text(Fixtures.DOWNLOAD_PAGE), downloadUrl)
                .expect()

            // Heading and panel are **siblings**, not nested. Looking for the size inside the
            // heading finds nothing and does not fail: it would be a silently empty field, which is
            // what `HtmlPage` exists to make impossible. The current variant has no size — it is
            // the page you are already on.
            assertThat(versions.drop(1).mapNotNull { it.sizeBytes }).hasSize(Fixtures.APP_VARIANTS - 1)
        }

        @Test
        @DisplayName("with no accordion the current version remains, taken from the heading")
        fun aPageWithoutTheAccordionStillYieldsOneVersion() {
            val versions = downloadParser
                .parseVersions(Fixtures.text(Fixtures.DOWNLOAD_PAGE_SINGLE), downloadUrl)
                .expect()

            assertThat(versions).hasSize(1)
            assertThat(versions.single().versionName).isEqualTo(Fixtures.SINGLE_VERSION)
            assertThat(versions.single().ref.value).isEqualTo(ModyoloRefs.FIRST_VARIANT.toString())
        }

        @Test
        @DisplayName("a broken variant selector degrades to the current one, not to zero")
        fun aBrokenVariantSelectorDegradesInsteadOfEmptying() {
            val broken = ModyoloConfig(selectors = ModyoloSelectors(versionItem = "#does-not-exist > div"))
            val versions = ModyoloDownloadParser(broken)
                .parseVersions(Fixtures.text(Fixtures.DOWNLOAD_PAGE), downloadUrl)
                .expect()

            // **The degradation is deliberate, and not free: it hides a broken selector.** Worth
            // saying, because it is not an oversight. A page with no accordion and a page whose
            // accordion no longer parses are **indistinguishable**: modyolo omits the section
            // entirely when there is a single variant, so no signal says "there should have been a
            // list here". Between the two possible readings, the one that keeps the app working was
            // chosen — the old-version list is lost, the download is not.
            //
            // What does **not** degrade is the heading: if that disappeared too, the fallback would
            // have no version name left and the page would fail. The test below checks it.
            assertThat(versions).hasSize(1)
            assertThat(versions.single().versionName).isEqualTo("${Fixtures.APP_VERSION} MOD")
        }

        @Test
        @DisplayName("if the heading disappears too, the page fails instead of inventing")
        fun aBrokenHeadingFails() {
            val broken = ModyoloConfig(
                selectors = ModyoloSelectors(
                    versionItem = "#does-not-exist > div",
                    downloadHeading = "h1.not-this-either",
                ),
            )
            val result = ModyoloDownloadParser(broken)
                .parseVersions(Fixtures.text(Fixtures.DOWNLOAD_PAGE_SINGLE), downloadUrl)

            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
            assertThat((result as StoreResult.Failure).error)
                .isInstanceOf(StoreError.ParseFailure::class.java)
        }
    }

    @Nested
    @DisplayName("URL normalisation")
    inner class Normalization {

        @Test
        @DisplayName("raw spaces are encoded, already-encoded ones are not")
        fun encodesOnlyWhatIsNotEncoded() {
            val raw = ModyoloRefs.normalizeFileUrl("https://x.example/Bloons TD 6/a b.apk")
            val already = ModyoloRefs.normalizeFileUrl("https://x.example/The%20Walking%20Zombie/a.apk")

            assertThat(raw).isEqualTo("https://x.example/Bloons%20TD%206/a%20b.apk")
            // Encoding twice would give `%2520`, and the file would not exist. These are the two
            // forms modyolo's CDN mixes: old entries already encoded, new ones not.
            assertThat(already).isEqualTo("https://x.example/The%20Walking%20Zombie/a.apk")
        }

        @Test
        @DisplayName("non-ASCII characters become percent-encoded UTF-8")
        fun encodesNonAscii() {
            val url = ModyoloRefs.normalizeFileUrl("https://x.example/My Radio - România/a.apk")

            assertThat(url).contains("Rom%C3%A2nia")
        }
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixtures, gave Unsupported")
    }

    private companion object {
        const val SEARCH_RESULTS = 20
    }
}
