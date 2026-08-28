package com.multistore.store.pdalife

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.DownloadHint
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreAdapterContractTest
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * pdalife against the contract every adapter must satisfy.
 *
 * It is the **seventh** to extend it and the **second** `USER_ASSISTED_ONLY` after uptodown. The
 * difference between the two is instructive and lies entirely in `getDownloadLink`: uptodown makes
 * an extra request because its download page publishes the SHA-256, and that request turns an
 * assisted download into a verified one. Here there is nothing to buy — no hash on the site — and
 * indeed with a `VersionRef` in hand the adapter asks nobody anything.
 */
@DisplayName("Contract — pdalife")
class PdalifeStoreAdapterContractTest : StoreAdapterContractTest() {

    private lateinit var server: MockWebServer
    private lateinit var fake: PdalifeTestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var pdalife: PdalifeStoreAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = PdalifeTestServer(server)
        val work = Files.createTempDirectory("pdalife-contract").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        pdalife = PdalifeStoreAdapter(
            config = PdalifeConfig(baseUrl = fake.baseUrl),
            clients = clients,
        )
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    override fun adapter(): StoreAdapter = pdalife

    override val queryWithResults: String = Fixtures.QUERY_WITH_RESULTS

    override val queryWithoutResults: String = Fixtures.QUERY_WITHOUT_RESULTS

    override val existingRef: StoreAppRef = StoreAppRef(Fixtures.APP_REF)

    /** A well-formed stem that does not exist: the page is requested and really answers 404. */
    override val missingRef: StoreAppRef = StoreAppRef("qzxvnpwmklj-does-not-exist-android-a99999999")

    override val existingVersionRef: VersionRef = VersionRef(Fixtures.APP_OLD_DOWNLOAD_HASH)

    // --- Beyond the contract: what holds for this store only ------------------------------

    @Test
    @DisplayName("the User-Agent actually sent is not the library default")
    fun userAgentReachesTheWire() = runTest {
        pdalife.search(queryWithResults)
        val sent = fake.received.mapNotNull { it.headers["User-Agent"] }
        assertThat(sent).isNotEmpty()
        sent.forEach { assertThat(it).isEqualTo(PdalifeConfig.DEFAULT_USER_AGENT) }
    }

    /**
     * The query is normalised **before** being sent, not after following two redirects.
     *
     * `/search/plus%20messenger/` answers 301 towards `/search/plus-messenger/page-1`, which
     * answers another. The test looks at the URL that goes out, which is the only way to prove it:
     * a test on the outcome would pass while following the redirects too.
     */
    @Test
    @DisplayName("the query arrives already slugified, and page 1 has no segment")
    fun queryIsSluggedBeforeSending() = runTest {
        pdalife.search("Plus  Messenger!", page = 0)
        pdalife.search("Plus  Messenger!", page = 1)

        val paths = fake.received.map { it.url.encodedPath }
        assertThat(paths).containsExactly("/search/plus-messenger/", "/search/plus-messenger/page-2/")
            .inOrder()
    }

    /**
     * A query made only of punctuation does not become a request.
     *
     * `/search/c%2B%2B/` answers **404** on the real site, and an empty slug would give
     * `/search//`, i.e. a different page from the one intended. The outcome is zero results and
     * zero requests — not a store error, because the store has nothing to do with it.
     */
    @Test
    @DisplayName("a query that reduces to nothing is never sent")
    fun punctuationOnlyQueryIsNotSent() = runTest {
        val result = pdalife.search("+++")
        assertThat(result).isInstanceOf(StoreResult.Success::class.java)
        assertThat((result as StoreResult.Success).value.items).isEmpty()
        assertThat(fake.received).isEmpty()
    }

    /**
     * With a `VersionRef` in hand the download costs **no** request.
     *
     * A version's ref *is* the octet composing the first hop's URL. That is the difference from
     * uptodown, where the extra request exists to read the hash: here there is no hash, so asking
     * for the page would be paying for nothing.
     */
    @Test
    @DisplayName("downloading a known version does not query the store")
    fun downloadOfAKnownVersionCostsNothing() = runTest {
        val resolution = pdalife.getDownloadLink(existingRef, existingVersionRef)

        assertThat(resolution).isInstanceOf(StoreResult.Success::class.java)
        val assisted = (resolution as StoreResult.Success).value as DownloadResolution.UserAssisted
        assertThat(assisted.pageUrl).endsWith("/dwn/${Fixtures.APP_OLD_DOWNLOAD_HASH}.html")
        // `CHOOSE_A_MIRROR` and not `TAP_DOWNLOAD_BUTTON`: on the real page the button labelled
        // "Download now" is an advert. See the note in the adapter.
        assertThat(assisted.hint).isEqualTo(DownloadHint.CHOOSE_A_MIRROR)
        // No hash anywhere on the site: pre-install verification will compute it and report it as
        // "not contradicted". `expectedSize` stays null because `68.83 Mb` is rounded.
        assertThat(assisted.expectedSha256).isNull()
        assertThat(assisted.expectedSize).isNull()
        assertThat(fake.received).isEmpty()
    }

    /** Without a version the listing is read, and the version chosen is the most recent. */
    @Test
    @DisplayName("without a version the most recent is taken, with a single request")
    fun downloadWithoutVersionReadsTheListing() = runTest {
        val resolution = pdalife.getDownloadLink(existingRef, version = null)

        val assisted = (resolution as StoreResult.Success).value as DownloadResolution.UserAssisted
        assertThat(assisted.pageUrl).endsWith("/dwn/${Fixtures.APP_DOWNLOAD_HASH}.html")
        assertThat(fake.received.map { it.url.encodedPath })
            .containsExactly("/${Fixtures.APP_REF}.html")
    }

    /**
     * **The adapter does not read the landing page, and this test says why.**
     *
     * The first hop is a 301 towards `mobdisc.com`. That page has three `a.b-download__button`s:
     * the real one starts **disabled** with a fake `href` (`#/download/…`), and the other two are
     * adverts — one of which, on the modified game's listing, is
     * `https://api.monstervpn.cc/media/apk_versions/monsterVPN-2.4.3.apk`, i.e. **a real APK of
     * another app**.
     *
     * The test proves it positively, on the real page's fixture: it shows that the obvious fallback
     * — "take the first link ending in `.apk`" — returns the advert, and that the only link with
     * the right octet is the inert one. There is nothing to read there without really running the
     * reCAPTCHA, and that is why the user opens the page.
     */
    @Test
    @DisplayName("on the landing page the first .apk is an advert, not the app")
    fun theLandingPageOffersAnAdvertApk() {
        val page = HtmlPage.of(Fixtures.html(Fixtures.DOWNLOAD_MOD), MOBDISC_URL)

        val buttons = page.all("a.b-download__button")
        assertThat(buttons).hasSize(3)

        // The obvious fallback — "take the link ending in `.apk`" — finds **two**, and neither is
        // the app. The first is the real button, whose `href` is a fragment that, resolved, points
        // at **the page itself**: downloading it would give the HTML under a name ending in
        // `.apk`. The second is an advert, and that one is a real APK — of another app.
        val apkUrls = buttons.mapNotNull { it.ownAbsUrlOrNull("href") }.filter { it.endsWith(".apk") }
        assertThat(apkUrls).hasSize(2)
        assertThat(apkUrls.first()).startsWith(MOBDISC_URL)
        assertThat(apkUrls.first()).contains("#/download/")
        assertThat(apkUrls.last()).contains("monstervpn")

        val real = page.one("a.js-dwn-btn")
        assertThat(real.ownAttrOrNull("class")).contains("b-download__button_state_inactive")
        assertThat(real.ownAttrOrNull("href")).startsWith("#/download/")
        assertThat(real.ownAttrOrNull("data-dwn")).isEqualTo(Fixtures.MOD_DOWNLOAD_HASH)

        // And the reason that button is inert: the token is produced by reCAPTCHA v3.
        assertThat(Fixtures.html(Fixtures.DOWNLOAD_MOD)).contains(RECAPTCHA_KEY)
    }

    /**
     * A listing that exists but offers nothing to download is `NotFound`, not an empty success.
     *
     * Opening the WebView on a URL built from an empty list would send the user to `/dwn/.html`.
     */
    @Test
    @DisplayName("a listing with no versions produces no download URL")
    fun listingWithoutVersionsIsNotFound() = runTest {
        // The real listing with only the versions block removed: it stays pdalife markup, and it
        // stays a page the parser can read. The case has no fixture because the site does not
        // produce it — every sampled listing has at least one version — but "the page is there and
        // offers nothing" is exactly the shape a withdrawn app would take.
        fake.rawOverrides["/${Fixtures.APP_REF}.html"] =
            Fixtures.html(Fixtures.DETAIL).replace(VERSIONS_BLOCK, "")

        val resolution = pdalife.getDownloadLink(existingRef, version = null)

        assertThat(resolution).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((resolution as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    private companion object {
        const val MOBDISC_URL = "https://mobdisc.com/dw6d2d7bca/download.html"
        const val RECAPTCHA_KEY = "6Lceo_8UAAAAAGKPGkR-373630tIcnJuXBybKBGp"

        /** The container holding the listing's four `accordion-item`s together. */
        const val VERSIONS_BLOCK = "accordion-item js-accordion-item"
    }
}
