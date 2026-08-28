package com.multistore.store.pdalife.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.store.api.StoreResult
import com.multistore.store.pdalife.Fixtures
import com.multistore.store.pdalife.PdalifeConfig
import kotlin.time.Instant
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * pdalife's feed, and it carries the most concrete finding here: **five entries in a hundred are
 * dated in the future**, the furthest at 2029.
 */
@DisplayName("pdalife — the recent-updates feed")
class PdalifeFeedParserTest {

    private val config = PdalifeConfig(baseUrl = BASE_URL)
    private val parser = PdalifeFeedParser(config)

    private fun parse(now: Instant = NOW) =
        parser.parse(Fixtures.html(Fixtures.RECENT_FEED), "$BASE_URL/rss/", page = 0, now = now)

    @Test
    @DisplayName("reads all one hundred entries")
    fun readsEveryItem() {
        assertThat(parse().expect().items).hasSize(ITEMS_IN_FIXTURE)
    }

    @Test
    @DisplayName("five entries are dated in the future and stay undated")
    fun fiveEntriesAreDatedInTheFuture() {
        val items = parse().expect().items
        // The number is measured on the fixture, not chosen: they are announcements of unreleased
        // games. Without this rule they would stay at the top of the "recent" section forever — and
        // the section would show as its first five things that do not exist.
        assertThat(items.count { it.lastUpdated == null }).isEqualTo(FUTURE_DATED)
        assertThat(items.count { it.lastUpdated != null }).isEqualTo(ITEMS_IN_FIXTURE - FUTURE_DATED)
        // And they stay in the list: the app is there and its listing works, it is only the date
        // that is unusable.
        assertThat(items).hasSize(ITEMS_IN_FIXTURE)
    }

    @Test
    @DisplayName("the site's verb and the platform do not end up in the title")
    fun stripsTheDownloadPhrase() {
        val titles = parse().expect().items.map { it.title }
        assertThat(titles.filter { it.contains(config.selectors.feedTitleVerb) }).isEmpty()
        assertThat(titles.filter { it.contains("Android") }).isEmpty()
        assertThat(titles).contains("The Walking Dead: A New Frontier")
    }

    @Test
    @DisplayName("every entry is an Android listing, and the ref guarantees it")
    fun everyRefIsAnAndroidListing() {
        // The feed is already filtered — 100 out of 100 — but the ref stays the real defence: an
        // iOS entry would not produce a `…-android-aNNN` stem and would fall away.
        parse().expect().items.forEach { assertThat(it.ref.value).contains("-android-a") }
    }

    @Test
    @DisplayName("category and image come from the feed")
    fun readsCategoryAndImage() {
        val walkingDead = parse().expect().items.single { it.title == "The Walking Dead: A New Frontier" }
        assertThat(walkingDead.categories).containsExactly("Action")
        // Not an icon but the first screenshot: it is the only image the feed publishes.
        assertThat(walkingDead.iconUrl).endsWith("/1.jpg")
    }

    private fun <T> StoreResult<T>.expect(): T = (this as StoreResult.Success).value

    private companion object {
        const val BASE_URL = "https://pdalife.com"
        const val ITEMS_IN_FIXTURE = 100
        const val FUTURE_DATED = 5
        val NOW: Instant = Instant.parse("2026-08-26T00:00:00Z")
    }
}
