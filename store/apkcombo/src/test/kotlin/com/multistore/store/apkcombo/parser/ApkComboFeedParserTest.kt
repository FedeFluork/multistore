package com.multistore.store.apkcombo.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.apkcombo.Fixtures
import kotlin.time.Instant
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * apkcombo's new-releases feed, read against the real page.
 *
 * The three things these tests hold fast are the three that, wrong, would not be visible: the
 * document must be read as XML and not as HTML, the feed's prefix is not part of the name, and the
 * `packageName` is there — on this store and on none of the other new-release sources.
 */
@DisplayName("apkcombo — the new-releases feed")
class ApkComboFeedParserTest {

    private val config = ApkComboConfig(baseUrl = BASE_URL)
    private val parser = ApkComboFeedParser(config)

    private fun parse(now: Instant = NOW) =
        parser.parse(Fixtures.html(Fixtures.RECENT_FEED), "$BASE_URL/latest-updates/feed", page = 0, now = now)

    @Test
    @DisplayName("reads every entry of the real feed")
    fun readsEveryItem() {
        val result = parse().expect()
        assertThat(result.items).hasSize(ITEMS_IN_FIXTURE)
        assertThat(result.hasMore).isFalse()
    }

    @Test
    @DisplayName("the feed prefix does not end up in the title")
    fun stripsFeedPrefix() {
        val titles = parse().expect().items.map { it.title }
        // The defence: without it every row on Home would start with the same bracket and the
        // title would not match the listing's — two apps to the identity matcher.
        assertThat(titles.filter { it.startsWith("[") }).isEmpty()
        assertThat(titles).contains("Recovery Reboot")
    }

    @Test
    @DisplayName("every entry carries the packageName, which lives in the URL")
    fun everyItemHasAPackageName() {
        val items = parse().expect().items
        // This is what makes this source different from the other three: step 4 of the pre-install
        // pipeline has something to compare against.
        assertThat(items.filter { it.packageName == null }).isEmpty()
        val recoveryReboot = items.single { it.title == "Recovery Reboot" }
        assertThat(recoveryReboot.packageName).isEqualTo("gt.recovery.reboot")
        assertThat(recoveryReboot.ref.value).isEqualTo("recovery-reboot/gt.recovery.reboot")
    }

    @Test
    @DisplayName("every entry's date is read")
    fun readsDates() {
        val dated = parse().expect().items.count { it.lastUpdated != null }
        assertThat(dated).isEqualTo(ITEMS_IN_FIXTURE)
    }

    @Test
    @DisplayName("a date in the future leaves the entry undated, it does not discard it")
    fun futureDatesAreDropped() {
        // A clock set back a year makes every date in the fixture "future": the way to test the
        // rule without waiting for 2029, which is the real date that motivated it (on pdalife, 5
        // entries out of 100).
        val result = parse(now = A_YEAR_BEFORE).expect()
        assertThat(result.items).hasSize(ITEMS_IN_FIXTURE)
        assertThat(result.items.filter { it.lastUpdated != null }).isEmpty()
    }

    @Test
    @DisplayName("read as HTML the feed has no links at all, and the parser says so")
    fun htmlParserWouldReadNothing() {
        // The proof that the XML entry point carries the weight. The fixture is not read with the
        // wrong parser — there is no way to do that from outside — but the link selector is
        // removed: the same outcome an empty-element `<link>` would produce, i.e. zero readable
        // entries over 98 present rows. The row mapper tells that from "empty feed".
        val blind = ApkComboFeedParser(
            ApkComboConfig(baseUrl = BASE_URL, selectors = config.selectors.copy(feedLink = "does-not-exist")),
        )
        val failure = blind.parse(
            Fixtures.html(Fixtures.RECENT_FEED),
            "$BASE_URL/latest-updates/feed",
            page = 0,
            now = NOW,
        )
        assertThat(failure).isInstanceOf(StoreResult.Failure::class.java)
        assertThat(((failure as StoreResult.Failure).error as StoreError.ParseFailure).selector)
            .isEqualTo(config.selectors.feedItem)
    }

    private fun <T> StoreResult<T>.expect(): T = (this as StoreResult.Success).value

    private companion object {
        const val BASE_URL = "https://apkcombo.com"
        const val ITEMS_IN_FIXTURE = 96
        val NOW: Instant = Instant.parse("2026-08-26T00:00:00Z")
        val A_YEAR_BEFORE: Instant = Instant.parse("2025-08-26T00:00:00Z")
    }
}
