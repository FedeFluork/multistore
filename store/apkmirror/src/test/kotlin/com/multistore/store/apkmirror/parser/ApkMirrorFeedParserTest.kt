package com.multistore.store.apkmirror.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.apkmirror.Fixtures
import kotlin.time.Instant
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * apkmirror's latest-releases feed.
 *
 * What is worth holding fast here is **the title split**: `{Name} {version} by {Developer}` is the
 * only new-release source of the four that publishes the developer, and also the only one where
 * the app's name arrives mixed with two other things.
 */
@DisplayName("apkmirror — the new-releases feed")
class ApkMirrorFeedParserTest {

    private val config = ApkMirrorConfig(baseUrl = BASE_URL)
    private val parser = ApkMirrorFeedParser(config)

    private fun parse(now: Instant = NOW) =
        parser.parse(Fixtures.html(Fixtures.RECENT_FEED), "$BASE_URL/feed/", page = 0, now = now)

    @Test
    @DisplayName("reads all ten entries")
    fun readsEveryItem() {
        assertThat(parse().expect().items).hasSize(ITEMS_IN_FIXTURE)
    }

    @Test
    @DisplayName("the title splits into name, version and developer")
    fun splitsTitle() {
        val betternet = parse().expect().items.first()
        // The commas inside a developer's name are why the cut is on the last occurrence of
        // " by " and not on a comma.
        assertThat(betternet.title).isEqualTo("Betternet: Secure VPN Hotspot")
        assertThat(betternet.latestVersionName).isEqualTo("8.20.0")
        assertThat(betternet.developer).isEqualTo("Betternet, LLC")
    }

    @Test
    @DisplayName("no title keeps the version number")
    fun noTitleKeepsItsVersion() {
        val titles = parse().expect().items.map { it.title }
        // The defence: a title that changes on every release is a new app on every release to the
        // identity matcher, and apkmirror publishes no packageName in the feed to correct itself
        // with.
        assertThat(titles.filter { it.contains(Regex("""\s\d[\d.]*$"""))}).isEmpty()
        assertThat(titles.filter { it.contains(" by ") }).isEmpty()
    }

    @Test
    @DisplayName("the icon is read from the entry's body, where the feed puts it")
    fun readsTheIcon() {
        val items = parse().expect().items
        // Ten out of ten, and not "at least one": if the feed stopped carrying it on half the
        // entries, Home would go back to showing the placeholder on exactly the rows the store
        // publishes first.
        assertThat(items.mapNotNull { it.iconUrl }).hasSize(ITEMS_IN_FIXTURE)
        assertThat(items.first().iconUrl).isEqualTo(
            "https://downloadr2.apkmirror.com/wp-content/uploads/2024/02/29/" +
                "65db6b9e41809_com.freevpnintouch-384x384.png",
        )
    }

    @Test
    @DisplayName("the ref is the listing, not the release the link came from")
    fun refPointsAtTheApp() {
        val refs = parse().expect().items.map { it.ref.value }
        // The link is a three-segment path. A three-segment ref would open that precise file's
        // page instead of the listing.
        refs.forEach { assertThat(it.count { c -> c == '/' }).isEqualTo(1) }
    }

    @Test
    @DisplayName("an app with \" by \" in its name does not lose half its title")
    fun titleWithByInsideIsSplitOnTheLastOccurrence() {
        // **A synthetic feed, and it has to be said why.** None of the ten captured entries has
        // two occurrences of " by ", so on the real fixture the first and last occurrence give the
        // same result: the injection swapping them stays **green**, and without this test the
        // defence would be a caption. The case is not invented — apps with " by " in their name
        // exist — but it was not in that day's feed, and waiting for it would mean noticing it from
        // a mangled title on Home.
        val feed = feedWith("Words by Post 3.1.4 by Fine Games Ltd.", "https://www.apkmirror.com/apk/fine-games/words-by-post/words-by-post-3-1-4-release/")

        val item = parser.parse(feed, BASE_URL, page = 0, now = NOW).expect().items.single()

        assertThat(item.title).isEqualTo("Words by Post")
        assertThat(item.developer).isEqualTo("Fine Games Ltd.")
        assertThat(item.latestVersionName).isEqualTo("3.1.4")
    }

    private fun feedWith(title: String, link: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><item>
          <title>$title</title>
          <link>$link</link>
          <pubDate>Tue, 25 Aug 2026 18:53:24 +0000</pubDate>
        </item></channel></rss>
    """.trimIndent()

    @Test
    @DisplayName("a date in the future leaves the entry undated")
    fun futureDatesAreDropped() {
        val result = parse(now = A_YEAR_BEFORE).expect()
        assertThat(result.items).hasSize(ITEMS_IN_FIXTURE)
        assertThat(result.items.filter { it.lastUpdated != null }).isEmpty()
    }

    private fun <T> StoreResult<T>.expect(): T = (this as StoreResult.Success).value

    private companion object {
        const val BASE_URL = "https://www.apkmirror.com"
        const val ITEMS_IN_FIXTURE = 10
        val NOW: Instant = Instant.parse("2026-08-26T00:00:00Z")
        val A_YEAR_BEFORE: Instant = Instant.parse("2025-08-26T00:00:00Z")
    }
}
