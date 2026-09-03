package com.multistore.store.apkcombo.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.apkcombo.ApkComboSelectors
import com.multistore.store.apkcombo.Fixtures
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import kotlin.time.Instant
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * apkcombo's parsers against the **real** pages.
 *
 * The contract test checks the adapter honours the contract's shape; these check the extracted
 * values are the right ones — the half the contract cannot look at. A parser returning `26` instead
 * of `70242` as a version code passes every structural check and proposes a downgrade on every
 * update.
 */
@DisplayName("apkcombo parsers")
class ApkComboParsersTest {

    private val config = ApkComboConfig(baseUrl = BASE)

    @Nested
    @DisplayName("Search")
    inner class Search {

        private val parser = ApkComboSearchParser(config)

        @Test
        fun `reads the twenty results with the packageName taken from the URL`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()

            assertThat(page.items).hasSize(20)
            val first = page.items.first()
            assertThat(first.title).isEqualTo("Telegram")
            assertThat(first.packageName).isEqualTo(Fixtures.APP_PACKAGE)
            assertThat(first.ref).isEqualTo(StoreAppRef(Fixtures.APP_PATH))
            assertThat(first.developer).isEqualTo("Telegram FZ-LLC")
            assertThat(first.categories).containsExactly("Communication")
        }

        @Test
        fun `the icon comes from the lazy-load attribute, not the transparent pixel`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()

            // The `src` attribute is a transparent pixel on **every** row: apkcombo lazy-loads.
            // Taking it would give twenty identical empty icons, a defect no "is not null"
            // assertion would catch.
            page.items.forEach { item ->
                assertThat(item.iconUrl).isNotNull()
                assertThat(item.iconUrl).doesNotContain("1.gif")
                assertThat(item.iconUrl).startsWith("https://")
            }
        }

        @Test
        fun `tells rating, downloads and size apart within one row`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()
            val first = page.items.first()

            // The three values sit in three sibling spans with no class telling them apart, in a
            // fixed order. Recognising them by position would work until a row has only two —
            // which happens to apps without a rating.
            assertThat(first.rating).isWithin(TOLERANCE).of(3.9f)
            assertThat(first.downloadsLabel).isEqualTo("1 B+")
        }

        @Test
        fun `a search with no results gives an empty list, not the whole page`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH_EMPTY), SEARCH_URL, page = 0).expect()

            assertThat(page.items).isEmpty()
            assertThat(page.hasMore).isFalse()
        }

        @Test
        fun `it never promises a next page`() {
            val page = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0).expect()

            // apkcombo ignores the page parameter: the second page is identical to the first,
            // verified by comparing the twenty links. Claiming more would give an infinite scroll.
            assertThat(page.hasMore).isFalse()
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
            val broken = ApkComboConfig(
                selectors = ApkComboSelectors(searchName = ".does-not-exist"),
            )
            val parser = ApkComboSearchParser(broken)

            val result = parser.parse(Fixtures.html(Fixtures.SEARCH), SEARCH_URL, page = 0)

            val error = (result as StoreResult.Failure).error as StoreError.ParseFailure
            assertThat(error.selector).isEqualTo(broken.selectors.searchItem)
        }
}

    @Nested
    @DisplayName("Detail")
    inner class Detail {

        private val parser = ApkComboDetailParser(config)
        private val ref = StoreAppRef(Fixtures.APP_PATH)

        @Test
        fun `reads the information table, version code included`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            assertThat(detail.summary.title).isEqualTo("Telegram")
            assertThat(detail.summary.latestVersionName).isEqualTo("12.10.0")
            // The version code is **only** here, in brackets and inside a span the site uses to
            // blur it graphically. It is the only number the anti-downgrade rule can work on: the
            // name has no relation to it.
            assertThat(detail.summary.latestVersionCode).isEqualTo(70_242L)
            assertThat(detail.summary.packageName).isEqualTo(Fixtures.APP_PACKAGE)
            assertThat(detail.summary.developer).isEqualTo("Telegram FZ-LLC")
            assertThat(detail.summary.categories).containsExactly("Communication")
            assertThat(detail.summary.contentKind).isEqualTo(ContentKind.APP)
            assertThat(detail.summary.downloadsLabel).isEqualTo("1,000,000,000+")
        }

        @Test
        fun `the update date is a date, not a string`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            // `Aug 21, 2026`, UTC midnight. Needed to order "recently updated" and to decide
            // whether a cached listing is old.
            assertThat(detail.summary.lastUpdated).isEqualTo(Instant.parse("2026-08-21T00:00:00Z"))
        }

        @Test
        fun `the screenshots are there and are full resolution`() {
            val detail = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, ref).expect()

            assertThat(detail.screenshots).isNotEmpty()
            // One attribute carries the full-resolution version, the other the thumbnail: the
            // listing opens the first when the image is tapped.
            detail.screenshots.forEach { assertThat(it.url).startsWith("https://") }
        }

        @Test
        fun `if the URL's package and the page's disagree, the listing is discarded`() {
            val other = StoreAppRef("telegram/com.impostore.telegram")

            val result = parser.parse(Fixtures.html(Fixtures.DETAIL), DETAIL_URL, other)

            // The worst case of all: showing one app's listing and installing another. The hard
            // block in the pre-install pipeline would stop it, but only **after** the download —
            // and the user would have been looking at the wrong listing from the start.
            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
            assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
        }

        @Test
        fun `the 404 page does not produce a listing`() {
            val result = parser.parse(Fixtures.html(Fixtures.NOT_FOUND), DETAIL_URL, ref)

            // apkcombo's 404 is a complete 55 KB page, with menu, search and suggestions: it is
            // plausible that a selector finds something in it.
            assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
            assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
        }
    }

    @Nested
    @DisplayName("Download")
    inner class Download {

        private val parser = ApkComboDownloadParser(config)

        @Test
        fun `extracts the four variants with type, size and ABI`() {
            val variants = parser.parse(
                Fixtures.html(Fixtures.DOWNLOAD),
                DOWNLOAD_URL,
                Fixtures.APP_TITLE,
            ).expect()

            assertThat(variants).hasSize(4)
            assertThat(variants.map { it.artifactType })
                .containsAtLeast(ArtifactType.APK, ArtifactType.XAPK)
            variants.forEach { variant ->
                assertThat(variant.versionName).isEqualTo(variant.versionName.trimStart())
                assertThat(variant.versionName).doesNotContain(Fixtures.APP_TITLE)
                assertThat(variant.sizeBytes).isNotNull()
                assertThat(variant.url).startsWith("https://")
            }
        }

        @Test
        fun `the signed URL is decoded from the query, not followed`() {
            val best = parser.parse(
                Fixtures.html(Fixtures.DOWNLOAD),
                DOWNLOAD_URL,
                Fixtures.APP_TITLE,
            ).expect().first { it.recommended }

            // The `href` wraps a percent-encoded URL. Asking for the redirect would work, but it
            // would cost a hop **on their servers** for a value already in hand.
            assertThat(best.url).contains("r2.cloudflarestorage.com")
            assertThat(best.url).doesNotContain("%3A%2F%2F")
            assertThat(best.versionCode).isEqualTo(70_242L)
            assertThat(best.minSdk).isEqualTo(23)
            assertThat(best.abis).containsExactly("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        @Test
        fun `the file name and the expiry come from the signed URL`() {
            val best = parser.parse(
                Fixtures.html(Fixtures.DOWNLOAD),
                DOWNLOAD_URL,
                Fixtures.APP_TITLE,
            ).expect().first { it.recommended }

            // The store chooses the name in the signed content disposition; the R2 object key
            // would say nothing on screen.
            assertThat(best.fileName).isEqualTo("Telegram_12.10.0_apkcombo.com.apk")
            // The signing date plus a four-hour expiry. Without an expiry, a resolution reused
            // later would give a 403 that looks like a block.
            assertThat(best.expiresAt).isEqualTo(Instant.parse("2026-08-24T19:00:04Z"))
        }

        /**
         * **The two panels are deduplicated by object key, not by URL**, and this is the only place
         * that proves it.
         *
         * The document is **constructed**, and the reason is worth stating rather than hiding: the
         * committed fixture happens to carry byte-identical signed URLs in both panels, so
         * deduplicating on either field gives the same answer there and the case cannot be
         * expressed. It is not hypothetical. On 03/09/2026 the live Spotify page served the
         * recommended `.apks` twice with URLs differing in one character — `X-Amz-Expires=14399`
         * against `14400`, because each anchor carries its own signature and the two panels were
         * signed a moment apart. Keyed on the URL both survived, `getAppDetails` published two
         * versions with the **same** `VersionRef` (which comes from the object key), and the canary
         * went red on the one invariant in that class that is about us and not about the store.
         *
         * The intermittence is the point: whether the two signatures land on the same second
         * decides it, so the real page is usually fine and this defect would return as an
         * unreproducible red. `ApkComboVariant.objectKey` is documented as the variant's identity,
         * and a signed URL is exactly what is not one.
         */
        @Test
        fun `the same file in both panels is one variant, however it was signed`() {
            fun signed(expires: Int) = (
                "https%3A%2F%2Fapks.r2.cloudflarestorage.com%2Fcom.spotify.music%2F9.1%2F" +
                    "145505446.d42fec1a.apks%3Fresponse-content-disposition%3Dattachment%253B" +
                    "%2520filename%253D%2522Spotify_9.1_apkcombo.com.xapk%2522%26" +
                    "X-Amz-Date%3D20260903T093608Z%26X-Amz-Expires%3D$expires"
                )

            fun panel(id: String, expires: Int) = """
                <div id="$id"><div class="tree"><ul><li><code>arm64-v8a</code>
                  <a class="variant" href="/r2?u=${signed(expires)}">
                    <span class="vername">9.1</span><span class="vercode">(145505446)</span>
                    <span class="vtype"><span class="type-xapk">XAPK</span></span>
                    <span class="description"><span class="spec">224 MB</span></span>
                  </a>
                </li></ul></div></div>
            """.trimIndent()

            // The same object key in both panels, signed one second apart.
            val html = "<html><body>" +
                panel("best-variant-tab", 14400) +
                panel("variants-tab", 14399) +
                "</body></html>"

            val variants = parser.parse(html, DOWNLOAD_URL, Fixtures.APP_TITLE).expect()

            assertThat(variants).hasSize(1)
            // And the survivor is the one apkcombo marked as recommended, which is what the
            // ordering of the two panels in `parse` is for.
            assertThat(variants.single().recommended).isTrue()
            // The invariant the canary states, asserted here where it can be reached offline: as
            // many distinct refs as there are variants.
            assertThat(variants.map { it.objectKey }.toSet()).hasSize(variants.size)
        }

        @Test
        fun `an older version's page has the same structure`() {
            val variants = parser.parse(
                Fixtures.html(Fixtures.DOWNLOAD_OLD),
                DOWNLOAD_URL,
                Fixtures.APP_TITLE,
            ).expect()

            assertThat(variants).isNotEmpty()
            // The variants of one release have different version codes among themselves: the same
            // version name published for different ABIs.
            assertThat(variants.mapNotNull { it.versionCode }.distinct().size).isAtLeast(2)
        }
    }

    @Nested
    @DisplayName("Old versions")
    inner class OldVersions {

        private val parser = ApkComboVersionsParser(config)

        @Test
        fun `lists the releases with date and minSdk but without a version code`() {
            val versions = parser.parse(Fixtures.html(Fixtures.OLD_VERSIONS), OLD_URL).expect()

            assertThat(versions.map { it.versionName }).containsExactly("12.10.0", "12.9.2", "12.9.1")
            assertThat(versions[0].minSdk).isEqualTo(23)
            assertThat(versions[1].minSdk).isEqualTo(21)
            assertThat(versions[0].publishedAt).isEqualTo(Instant.parse("2026-08-23T00:00:00Z"))

            // **No version code, and that is not a parser defect**: the page does not publish it.
            // Deriving it from the name would give a number with no relation to the real one and
            // would break every version comparison.
            assertThat(versions.mapNotNull { it.versionCode }).isEmpty()
        }
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixture, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixture, gave Unsupported")
    }

    private companion object {
        const val BASE = "https://apkcombo.com"
        const val SEARCH_URL = "$BASE/search/telegram"
        const val DETAIL_URL = "$BASE/telegram/org.telegram.messenger/"
        const val DOWNLOAD_URL = "$BASE/telegram/org.telegram.messenger/download/apk"
        const val OLD_URL = "$BASE/telegram/org.telegram.messenger/old-versions/"
        const val TOLERANCE = 0.001f
    }
}
