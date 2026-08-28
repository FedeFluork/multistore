package com.multistore.store.uptodown

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.Sha256
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
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * uptodown against the contract every adapter must satisfy.
 *
 * It is the **fourth** to extend it and the first `USER_ASSISTED_ONLY`: the contract has covered
 * the case from the start — `DownloadMode` has had four values from the beginning — and this is the
 * first time anyone really exercises it.
 */
@DisplayName("Contract — uptodown")
class UptodownStoreAdapterContractTest : StoreAdapterContractTest() {

    private lateinit var server: MockWebServer
    private lateinit var fake: UptodownTestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var uptodown: UptodownStoreAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = UptodownTestServer(server)
        val work = Files.createTempDirectory("uptodown-contract").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        uptodown = UptodownStoreAdapter(
            config = UptodownConfig(baseUrl = fake.baseUrl, appUrlTemplate = fake.appUrlTemplate),
            clients = clients,
        )
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    override fun adapter(): StoreAdapter = uptodown

    override val queryWithResults: String = Fixtures.QUERY_WITH_RESULTS

    override val queryWithoutResults: String = Fixtures.QUERY_WITHOUT_RESULTS

    override val existingRef: StoreAppRef = StoreAppRef(Fixtures.APP_SLUG)

    /** A well-formed slug that does not exist: the page is requested and really answers 404. */
    override val missingRef: StoreAppRef = StoreAppRef("qzxvnpwmklj-does-not-exist")

    override val existingVersionRef: VersionRef = VersionRef(Fixtures.OLD_VERSION_ID)

    // --- Beyond the contract: what holds for this store only ------------------------------

    @Test
    @DisplayName("the User-Agent actually sent is not the library default")
    fun userAgentReachesTheWire() = runTest {
        uptodown.search(queryWithResults)
        val sent = fake.received.mapNotNull { it.headers["User-Agent"] }
        assertThat(sent).isNotEmpty()
        sent.forEach { assertThat(it).isEqualTo(UptodownConfig.DEFAULT_USER_AGENT) }
    }

    @Test
    @DisplayName("a search with no results does not pick up the twelve 'Apps you're gonna love' cards")
    fun emptySearchDoesNotPickUpTheSuggestions() = runTest {
        val page = uptodown.search(queryWithoutResults).expect()

        // **This is the case this store makes more insidious than any other.** On a query with no
        // results uptodown emits no `#content-list` at all, and in its place shows twelve cards
        // with markup identical to the results' — `div.item`, `data-code`, icon, `.name a > h2`.
        // Telegram is among them. A bare `.item` selector would answer "Telegram" to a search that
        // found nothing, and with nine stores in parallel the aggregation would have no way of
        // knowing it is irrelevant.
        assertThat(page.items).isEmpty()
    }

    @Test
    @DisplayName("the search really reads the thirty-six results")
    fun searchReadsEveryResult() = runTest {
        val page = uptodown.search(queryWithResults).expect()

        assertThat(page.items).hasSize(SEARCH_RESULTS)
        assertThat(page.items.first().ref).isEqualTo(existingRef)
        assertThat(page.items.first().title).isEqualTo(Fixtures.APP_TITLE)
        // The slug is read from the **subdomain**, not from the path: it is the only store where
        // identity lives in the host.
        assertThat(page.items.map { it.ref.value }.toSet()).hasSize(page.items.size)
    }

    @Test
    @DisplayName("the second page is empty and costs no request")
    fun secondPageIsEmptyWithoutFetching() = runTest {
        uptodown.search(queryWithResults, page = 0).expect()
        val afterFirst = fake.received.size

        val second = uptodown.search(queryWithResults, page = 1).expect()

        // `?page=2` returns the **same 36 apps** in a different order: the order is randomised
        // server-side. Paginating would not even give stable results.
        assertThat(second.items).isEmpty()
        assertThat(fake.received.size).isEqualTo(afterFirst)
    }

    @Test
    @DisplayName("the listing carries the real package, which is not the one the name suggests")
    fun detailCarriesTheRealPackageName() = runTest {
        val detail = uptodown.getAppDetails(existingRef).expect()

        // uptodown redistributes `org.telegram.messenger.web`, not `org.telegram.messenger`. They
        // are two distinct apps to Android, and it is exactly the difference step 4 of the pipeline
        // compares: deducing the package from the title would give a verification that blocks a
        // legitimate installation.
        assertThat(detail.summary.packageName).isEqualTo(Fixtures.APP_PACKAGE)
    }

    @Test
    @DisplayName("the version list is the complete one, and every row identifies a file")
    fun versionsAreTheCompleteList() = runTest {
        val versions = uptodown.getVersions(existingRef).expect()

        // The list at the foot of the listing shows six and **skips the current one**;
        // `/versions` carries twenty, current included. That is what the second request is for.
        assertThat(versions).hasSize(VERSIONS)
        assertThat(versions.map { it.ref }.toSet()).hasSize(versions.size)
        assertThat(versions.first().ref).isEqualTo(VersionRef(Fixtures.CURRENT_FILE_ID))
        // `Android + 5.0`: the `+` comes **before** the number, and without allowing for it the
        // `minSdk` would be null on every version of every app on this store.
        assertThat(versions.mapNotNull { it.minSdk }).hasSize(versions.size)
        assertThat(versions.first().minSdk).isEqualTo(LOLLIPOP)
    }

    @Test
    @DisplayName("the hash is on the current version, attached by file identifier")
    fun theCurrentVersionCarriesItsHash() = runTest {
        val detail = uptodown.getAppDetails(existingRef).expect()

        val current = detail.versions.first { it.ref == VersionRef(Fixtures.CURRENT_FILE_ID) }
        assertThat(current.sha256).isEqualTo(Sha256.parseOrNull(Fixtures.CURRENT_SHA256))
        // The others do not have it: the listing publishes the hash of **one** file, and asking
        // for twenty would cost twenty requests. `providesHash = SOMETIMES` is that sentence.
        assertThat(detail.versions.count { it.sha256 != null }).isEqualTo(1)
    }

    @Test
    @DisplayName("'Certificate signature' is MD5 and does not end up in the signer")
    fun theCertificateSignatureIsNotASha256() = runTest {
        val detail = uptodown.getAppDetails(existingRef).expect()

        // uptodown writes `26babc62540ef0c20bfc6bacf3d3b1f5` under an icon called
        // `icon-40-sha256`: those are 32 characters, i.e. MD5. Putting it in `signerSha256` would
        // give a signature comparison that always fails — and it would be the costliest possible
        // error, because it would ask the user to uninstall for a legitimate update.
        assertThat(Fixtures.CERTIFICATE_MD5.length).isNotEqualTo(Sha256.HEX_LENGTH)
        detail.versions.forEach { assertThat(it.signerSha256).isNull() }
        assertThat(detail.preferredSignerSha256).isNull()
    }

    @Test
    @DisplayName("the download is assisted and carries the hash to verify it with")
    fun assistedDownloadCarriesTheHash() = runTest {
        val resolution = uptodown.getDownloadLink(existingRef).expect()

        val assisted = resolution as? DownloadResolution.UserAssisted
            ?: error("uptodown declares USER_ASSISTED_ONLY but gave ${resolution::class.simpleName}")
        // The page **does not contain** a link to the file: the button is a `<button>` running a
        // Turnstile and then calling an AJAX endpoint. Calling it without having run the challenge
        // would be pretending to have solved it.
        assertThat(assisted.pageUrl).contains(UptodownConfig.DOWNLOAD_SEGMENT)
        // Not `SOLVE_CAPTCHA`: the widget is `interaction-only` and almost never appears. The
        // instruction has to describe the normal case.
        assertThat(assisted.hint).isEqualTo(DownloadHint.TAP_DOWNLOAD_BUTTON)
        // **The extra request exists for this line.** Without it, the assisted path would be the
        // only one where the installed file is compared against nothing.
        assertThat(assisted.expectedSha256).isEqualTo(Sha256.parseOrNull(Fixtures.CURRENT_SHA256))
        // `78.85 MB` is rounded: an inexact expectation is worse than no expectation.
        assertThat(assisted.expectedSize).isNull()
    }

    @Test
    @DisplayName("an older version carries its own hash, not the current one's")
    fun anOlderVersionCarriesItsOwnHash() = runTest {
        val resolution = uptodown.getDownloadLink(existingRef, existingVersionRef).expect()

        val assisted = resolution as DownloadResolution.UserAssisted
        assertThat(assisted.pageUrl).endsWith(Fixtures.OLD_VERSION_ID)
        // If the `VersionRef` were ignored, the page opened would be the current one and the
        // expected hash would belong to another file: verification would fail on a legitimate
        // download, which is the worst way to be wrong.
        assertThat(assisted.expectedSha256).isEqualTo(Sha256.parseOrNull(Fixtures.OLD_SHA256))
    }

    @Test
    @DisplayName("a 404 page does not produce an invented listing")
    fun notFoundPageYieldsNotFound() = runTest {
        fake.missing += "/app/${Fixtures.APP_SLUG}/${UptodownConfig.PLATFORM}"

        val result = uptodown.getAppDetails(existingRef)

        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    @Test
    @DisplayName("a ref that looks like a host does not become one")
    fun aRefThatLooksLikeAHostIsRejected() = runTest {
        // On this store the slug ends up inside a **hostname**, not inside a path: a ref with a dot
        // would add a subdomain level, and `evil.example.com` would produce a request to
        // `evil.example.com.en.uptodown.com` — or, with a domain registered for the purpose,
        // somewhere else entirely.
        val before = fake.received.size
        val result = uptodown.getAppDetails(StoreAppRef("evil.example.com"))

        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat(fake.received.size).isEqualTo(before)
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixtures, gave Unsupported")
    }

    private companion object {
        const val SEARCH_RESULTS = 36
        const val VERSIONS = 20
        const val LOLLIPOP = 21
    }
}
