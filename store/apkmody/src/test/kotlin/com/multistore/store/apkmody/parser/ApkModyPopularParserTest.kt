package com.multistore.store.apkmody.parser

import com.google.common.truth.Truth.assertThat
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmody.ApkModyConfig
import com.multistore.store.apkmody.Fixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * apkmody's chart, read from the structured-data list block.
 *
 * What has to be held fast is the **choice by type**: the page publishes more than one `ld+json`
 * block, and taking the first would give zero entries instead of an error.
 */
@DisplayName("apkmody — the chart")
class ApkModyPopularParserTest {

    private val config = ApkModyConfig(baseUrl = BASE_URL)
    private val parser = ApkModyPopularParser(config)

    @Test
    @DisplayName("reads every entry of the chart")
    fun readsEveryItem() {
        assertThat(popular().items).hasSize(POPULAR_ITEMS)
    }

    @Test
    @DisplayName("the order is the one the position declares")
    fun preservesDeclaredOrder() {
        assertThat(popular().items.take(3).map { it.title })
            .containsExactly("YouTube Premium", "PikPak", "Spotify Pro").inOrder()
    }

    @Test
    @DisplayName("the \"Mod APK\" suffix does not end up in the title")
    fun stripsSeoSuffix() {
        // apkmody attaches it to all twelve entries on this page and to no listing title: keeping
        // it would give two different apps for the same app, on the same store.
        assertThat(popular().items.map { it.title }.filter { it.endsWith("Mod APK") }).isEmpty()
    }

    @Test
    @DisplayName("the icon comes from the card, paired by ref and not by position")
    fun iconsComeFromTheCards() {
        val items = popular().items
        // Twelve out of twelve: the number that makes this a rule rather than a fallback. On the
        // search page the same site's cards carry covers and eighteen are placeholders — which is
        // why this reading lives here and not in the search parser.
        assertThat(items.mapNotNull { it.iconUrl }).hasSize(items.size)
        val youtube = items.first { it.ref.value == "apps/youtube-premium-app" }
        assertThat(youtube.iconUrl)
            .isEqualTo("https://cdn.topmongo.com/packages/app.revanced.android.youtube/icon_9aec24.png")
    }

    @Test
    @DisplayName("the package is not inferred from the icon's address")
    fun theIconUrlIsNotAPackageName() {
        // The icon path **looks** like it declares the package, and on eleven icons out of twelve
        // that segment is a plausible package name. It stays null all the same: the convention is
        // measured on APK files, no fixture allows checking it against the package the listing
        // declares, and a wrong package feeds cross-store identity and the verification's hard
        // block.
        assertThat(popular().items.mapNotNull { it.packageName }).isEmpty()
    }

    @Test
    @DisplayName("apps and games are told apart by the ref")
    fun contentKindComesFromTheRef() {
        val items = popular().items
        assertThat(items.single { it.title == "8 Ball Pool" }.contentKind.name).isEqualTo("GAME")
        assertThat(items.single { it.title == "YouTube Premium" }.contentKind.name).isEqualTo("APP")
    }

    @Test
    @DisplayName("the block is chosen by type, not by position")
    fun picksTheItemListByType() {
        // The page has more than one `ld+json` block — among them a breadcrumb list, which also
        // has positions and names. Asked for one that does not exist, the parser declares a parse
        // failure instead of returning the wrong list or an empty one.
        val blind = ApkModyPopularParser(
            ApkModyConfig(baseUrl = BASE_URL, selectors = config.selectors.copy(popularJsonLd = "script[data-none]")),
        )
        val failure = blind.parse(Fixtures.html(Fixtures.POPULAR), "$BASE_URL/popular", page = 0)
        assertThat(failure).isInstanceOf(StoreResult.Failure::class.java)
        assertThat(((failure as StoreResult.Failure).error as StoreError.ParseFailure).selector)
            .isEqualTo("script[data-none]")
    }

    @Test
    @DisplayName("a breadcrumb block before the list does not become the chart")
    fun anEarlierListIsNotMistakenForTheRanking() {
        // **A synthetic page, and the reason is that the fixture does not contain the case.** On
        // the captured chart page the list block is the **first** of the two, so removing the type
        // check changes nothing: the injection stays green and the defence looks useless.
        //
        // The case is real and common: a breadcrumb block also has list elements with positions and
        // names, and WordPress themes usually emit it **first**. Without the check the parser would
        // choose it and — finding no URLs in its entries — return **zero results with no error**,
        // which is the worse of the two failures: "the chart is empty" instead of "the format
        // changed".
        val page = """
            <html><head>
            <script type="application/ld+json">
              {"@context":"https://schema.org","@type":"BreadcrumbList","itemListElement":[
                {"@type":"ListItem","position":1,"name":"Home","item":"https://apkmody.mobi/"}]}
            </script>
            <script type="application/ld+json">
              {"@context":"https://schema.org","@type":"ItemList","itemListElement":[
                {"@type":"ListItem","position":1,"name":"Spotify Pro Mod APK","url":"https://apkmody.mobi/apps/spotify-pro"}]}
            </script>
            </head><body></body></html>
        """.trimIndent()

        val result = parser.parse(page, "$BASE_URL/popular", page = 0)

        val items = (result as StoreResult.Success).value.items
        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Spotify Pro")
    }

    private fun popular() =
        (parser.parse(Fixtures.html(Fixtures.POPULAR), "$BASE_URL/popular", page = 0) as StoreResult.Success).value

    private companion object {
        const val BASE_URL = "https://apkmody.mobi"
        const val POPULAR_ITEMS = 12
    }
}
