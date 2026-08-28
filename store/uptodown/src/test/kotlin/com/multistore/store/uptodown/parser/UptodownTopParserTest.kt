package com.multistore.store.uptodown.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.store.api.StoreResult
import com.multistore.store.uptodown.Fixtures
import com.multistore.store.uptodown.UptodownConfig
import com.multistore.store.uptodown.UptodownRefs
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * uptodown's downloads chart.
 *
 * Two things to hold still, and they are the ones that make no noise when wrong: the rank sits
 * **inside** the title, and the chart's container is not the search one.
 */
@DisplayName("uptodown — the downloads chart")
class UptodownTopParserTest {

    private val config = UptodownConfig(baseUrl = BASE_URL)
    private val parser = UptodownTopParser(config, UptodownRefs(config))
    private val searchParser = UptodownSearchParser(config, UptodownRefs(config))

    @Test
    @DisplayName("reads all ten entries")
    fun readsEveryItem() {
        assertThat(top().items).hasSize(TOP_ITEMS)
    }

    @Test
    @DisplayName("the rank number does not end up in the title")
    fun stripsRankPrefix() {
        val titles = top().items.map { it.title }
        // The defence: `<h2>1. Uptodown App Store</h2>`. Keeping it, the title would change on
        // every shuffle of the chart and would not match the listing's.
        assertThat(titles.filter { it.matches(Regex("""^\d+\..*""")) }).isEmpty()
        assertThat(titles.first()).isEqualTo("Uptodown App Store")
        assertThat(titles).contains("CapCut")
    }

    @Test
    @DisplayName("the page's order is the chart's order")
    fun preservesRankOrder() {
        // The number is removed from the title but the information is not lost: it is the order.
        assertThat(top().items.take(3).map { it.title })
            .containsExactly("Uptodown App Store", "MovieBox", "CapCut").inOrder()
    }

    @Test
    @DisplayName("the same page holds two lists, and only one is the chart")
    fun rankingHasItsOwnContainer() {
        // `/android/top` carries **two** lists: the numbered chart in `#list-top-items` and a strip
        // of suggestions in `#content-list`, which is the search container. Anchoring on `.item`
        // would merge them, and the Home screen would show 58 apps claiming they are the ten most
        // downloaded.
        val strip = searchParser.parse(
            Fixtures.html(Fixtures.TOP), "$BASE_URL/android/top", page = 0,
        )
        val stripItems = (strip as StoreResult.Success).value.items
        assertThat(stripItems).hasSize(SUGGESTION_STRIP_ITEMS)

        val ranking = top().items.map { it.title }.toSet()
        // The two lists are not the same: none of the strip's first three entries is in the
        // chart.
        assertThat(stripItems.take(3).map { it.title }.none { it in ranking }).isTrue()
    }

    @Test
    @DisplayName("the recently updated apps are read by the search parser")
    fun recentUsesTheSearchContainer() {
        // `/android/latest-updates` emits `#content-list`, i.e. the same container as search. This
        // test is why the adapter has no third parser.
        val recent = searchParser.parse(
            Fixtures.html(Fixtures.LATEST_UPDATES), "$BASE_URL/android/latest-updates", page = 0,
        )
        val items = (recent as StoreResult.Success).value.items
        assertThat(items).hasSize(RECENT_ITEMS)
        assertThat(items.first().title).isEqualTo("TwitCasting")
        // And it carries no rank numbers: it is not a ranking.
        assertThat(items.map { it.title }.filter { it.matches(Regex("""^\d+\..*""")) }).isEmpty()
    }

    private fun top() =
        (parser.parse(Fixtures.html(Fixtures.TOP), "$BASE_URL/android/top", page = 0) as StoreResult.Success).value

    private companion object {
        const val BASE_URL = "https://en.uptodown.com"
        const val TOP_ITEMS = 10
        const val RECENT_ITEMS = 48

        /** The "More of our Top apps for Android" strip at the foot of the chart page. */
        const val SUGGESTION_STRIP_ITEMS = 48
    }
}
