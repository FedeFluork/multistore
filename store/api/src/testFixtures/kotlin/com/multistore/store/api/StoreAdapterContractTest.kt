package com.multistore.store.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.VersionRef
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The contract every adapter must honour, in executable form.
 *
 * Every adapter extends `StoreAdapterContractTest`, and capabilities are declared honestly: one
 * declared `true` and not populated fails this test. This class is the second half of that
 * sentence.
 *
 * The criterion it is written to: **every assertion must be able to fail** on a plausible but
 * wrong adapter. A contract test that always passes protects nothing and gives the opposite
 * impression. It therefore does not check that the methods exist (the compiler does that) but that
 * what they declare matches what they return on the store's real fixtures.
 *
 * The subtype supplies an adapter already wired to its own fixtures — never a network.
 */
abstract class StoreAdapterContractTest {

    /** The adapter under test, with fixtures in place of the network. */
    protected abstract fun adapter(): StoreAdapter

    /** A query that returns at least one result on the fixtures. */
    protected abstract val queryWithResults: String

    /** A reference that exists in the fixtures. */
    protected abstract val existingRef: StoreAppRef

    /** A query that finds nothing on the fixtures. */
    protected open val queryWithoutResults: String = "qzxvnpwmklj-no-results"

    /**
     * A query that on the fixtures also returns content the store labels adult.
     *
     * Mandatory for whoever declares [FilterCapability.NSFW_CONTENT], and useless for everyone
     * else: it is what turns that capability from a declaration into a proof.
     */
    protected open val queryWithNsfwResults: String? = null

    /** A reference that does not exist. */
    protected open val missingRef: StoreAppRef = StoreAppRef("qzxvnpwmklj.package.does.not.exist")

    /** A version that exists, to test download resolution. */
    protected open val existingVersionRef: VersionRef? = null

    /**
     * [ref]'s listing, by whatever route the adapter serves it.
     *
     * It exists because not every store answers the detail with an HTTP request. A locally-indexed
     * store ([SearchSource.LOCAL_INDEX]) already has the listing at home, and its `getAppDetails`
     * returns `Unsupported` to say so. Without this hook the contract test would have to skip
     * every detail check for exactly the stores that have the most of it — screenshots, hash,
     * signer, history — i.e. give up checking where checking matters.
     */
    protected open suspend fun detailFor(ref: StoreAppRef): StoreResult<StoreListingDetail> =
        adapter().getAppDetails(ref)

    // --- Identity and metadata -----------------------------------------------------------------

    @Test
    @DisplayName("the store metadata is usable")
    fun metadataIsUsable() {
        val meta = adapter().metadata
        assertThat(meta.displayName).isNotEmpty()
        assertThat(meta.host).isNotEmpty()
        assertThat(meta.listingLanguage).isNotEmpty()
        assertSecureUrl(meta.baseUrl, "metadata.baseUrl")
    }

    @Test
    @DisplayName("the User-Agent is declared and is not a library default")
    fun userAgentIsDeclared() {
        val ua = adapter().capabilities.userAgent
        assertThat(ua).isNotEmpty()
        // apkmirror answers 403 with 153 bytes to `okhttp/*` and `curl/*`. Leaving the default
        // means being born already blocked, and the fact that it has not happened on this store
        // does not make it less wrong: adapters get copied from one another.
        listOf("okhttp", "curl", "java/", "python", "wget").forEach { forbidden ->
            assertThat(ua.lowercase()).doesNotContain(forbidden)
        }
    }

    @Test
    @DisplayName("the listing page is an address on the store, not on somebody else")
    fun listingUrlBelongsToTheStore() {
        val url = adapter().listingUrl(existingRef)
        // `null` is allowed — an adapter may have no human page to open — but if it answers, that
        // address ends up in an intent towards the user's browser: it must be HTTPS and it must be
        // **on this store**. A badly built path would send a person to a host the app never
        // mentioned to them.
        url ?: return
        assertSecureUrl(url, "listingUrl")

        // The comparison is with `metadata.baseUrl`'s host and not with `metadata.host`, and the
        // test bench makes the difference: here the base URL is the MockWebServer's, while
        // `metadata.host` stays the production constant. Comparing with the constant would make
        // this check green only in a world where it does not run.
        //
        // `endsWith` and not equality for uptodown, which serves every listing from a subdomain of
        // its own — `telegram.en.uptodown.com` under `en.uptodown.com`.
        val host = URI(url).host.orEmpty()
        val expected = URI(adapter().metadata.baseUrl).host.orEmpty()
        assertThat(host == expected || host.endsWith(".\$expected")).isTrue()
    }

    // --- Search --------------------------------------------------------------------------------

    @Test
    @DisplayName("search does what the capabilities declare")
    fun searchMatchesCapabilities() = runTest {
        val adapter = adapter()
        val result = adapter.search(queryWithResults)
        if (!adapter.capabilities.search) {
            assertThat(result).isInstanceOf(StoreResult.Unsupported::class.java)
            return@runTest
        }
        val page = result.expectSuccess("search(\"$queryWithResults\")")
        assertThat(page.items).isNotEmpty()
        page.items.forEach { item ->
            assertThat(item.storeId).isEqualTo(adapter.id)
            assertThat(item.title).isNotEmpty()
        }
    }

    @Test
    @DisplayName("a search with no results is an empty success, not an error")
    fun emptySearchIsSuccess() = runTest {
        val adapter = adapter()
        if (!adapter.capabilities.search) return@runTest
        // The distinction really matters: with nine stores in parallel, a "no results" treated as
        // an error would trip the circuit breaker of a perfectly healthy store.
        val page = adapter.search(queryWithoutResults).expectSuccess("search with no results")
        assertThat(page.items).isEmpty()
    }

    /**
     * Whoever declares they can filter adult content **proves** it, on real fixtures.
     *
     * A filter that does not filter is the worst failure of this family: the setting reads as on,
     * the user trusts it, and nothing happens. The only way to pass here is for the two sets to
     * differ — and for the filtered one to be contained in the other, because a filter that *adds*
     * results is answering a different question from the one asked.
     *
     * The inverse assertion matters as much and lives in the other branch: whoever does **not**
     * declare the capability must not change its answer according to the filter. An adapter
     * filtering on its own, without saying so, would make it impossible for the UI to know which
     * stores the setting really affects.
     */
    @Test
    @DisplayName("the adult-content filter does what the capabilities declare")
    fun nsfwFilterMatchesCapabilities() = runTest {
        val adapter = adapter()
        if (!adapter.capabilities.search) return@runTest
        val declared = FilterCapability.NSFW_CONTENT in adapter.capabilities.supportedFilters

        if (!declared) {
            val excluded = adapter.search(queryWithResults, SearchFilters.NONE)
                .expectSuccess("search without adult content")
            val included = adapter.search(queryWithResults, SearchFilters(includeNsfw = true))
                .expectSuccess("search with adult content")
            assertThat(excluded.items.map { it.ref }).isEqualTo(included.items.map { it.ref })
            return@runTest
        }

        val query = requireNotNull(queryWithNsfwResults) {
            "The adapter declares FilterCapability.NSFW_CONTENT: `queryWithNsfwResults` is " +
                "required, a query that on the fixtures also returns adult-labelled content. " +
                "Without it, the capability is a declaration nobody verifies."
        }
        val included = adapter.search(query, SearchFilters(includeNsfw = true))
            .expectSuccess("search(\"$query\") with adult content")
        val excluded = adapter.search(query, SearchFilters.NONE)
            .expectSuccess("search(\"$query\") without adult content")

        assertThat(included.items).isNotEmpty()
        assertThat(excluded.items.size).isLessThan(included.items.size)
        assertThat(included.items.map { it.ref }).containsAtLeastElementsIn(excluded.items.map { it.ref })
    }

    /**
     * `clientFilters` declares **exactly** the fields the fixtures carry on every row.
     *
     * The census made permanent. The decision to filter client-side "only on fields a measurement
     * shows always present" is worth as much as the measurement behind it, and a measurement
     * written in a document ages at the first updated fixture.
     *
     * **The equivalence holds in both directions, and the two errors differ.** Declaring a filter
     * a row cannot satisfy means discarding rows nothing is known about, presented as judged; not
     * declaring it when the field is always there means excluding this store from a search it
     * could have answered ([FilterPlan] does not query whoever cannot filter). The first lies, the
     * second costs results.
     */
    @Test
    @DisplayName("clientFilters declares exactly the fields every row carries")
    fun clientFiltersMatchTheFixtures() = runTest {
        val adapter = adapter()
        if (!adapter.capabilities.search) return@runTest
        val declared = adapter.capabilities.clientFilters

        assertWithMessage(
            "clientFilters may only contain filters decidable by looking at a list row: " +
                "minSdk and the anti-features live in AppVersion, the SORT_* values are not " +
                "filters, and an absent label cannot be inferred.",
        ).that(FilterCapability.entries.filter { it in declared && it !in ROW_FILTERS }).isEmpty()

        val page = adapter.search(queryWithResults).expectSuccess("search")
        assertThat(page.items).isNotEmpty()
        ROW_FILTERS.forEach { capability ->
            val populated = page.items.count { capability.isPopulatedOn(it) }
            val total = page.items.size
            assertWithMessage(
                "$capability: $populated rows out of $total carry it. " +
                    if (populated == total) {
                        "The field is always present, so it must be declared in clientFilters: " +
                            "without it, a filtered search excludes this store for nothing."
                    } else {
                        "The field is missing on ${total - populated} rows, so it must NOT be " +
                            "declared: a client-side filter would discard them without having " +
                            "judged them."
                    },
            ).that(declared.contains(capability)).isEqualTo(populated == total)
        }
    }

    /**
     * A filter the adapter does **not** declare must not change its answer.
     *
     * The inverse assertion of the previous one, protecting the same thing from the other side: an
     * adapter filtering on its own, without saying so, would make it impossible for the UI to know
     * which stores a filter actually affects — and [FilterPlan] would classify it as "cannot
     * filter" while it filters.
     */
    @Test
    @DisplayName("an undeclared filter does not change the adapter's answer")
    fun undeclaredFiltersDoNotChangeTheAnswer() = runTest {
        val adapter = adapter()
        if (!adapter.capabilities.search) return@runTest
        val supported = adapter.capabilities.supportedFilters
        val probe = SearchFilters(
            contentKind = if (FilterCapability.CONTENT_KIND in supported) null else ContentKind.GAME,
            minRating = if (FilterCapability.MIN_RATING in supported) null else 4.5f,
            maxMinSdk = if (FilterCapability.MIN_SDK in supported) null else 21,
        )
        val plain = adapter.search(queryWithResults, SearchFilters.NONE).expectSuccess("search")
        val filtered = adapter.search(queryWithResults, probe).expectSuccess("filtered search")
        assertThat(filtered.items.map { it.ref }).isEqualTo(plain.items.map { it.ref })
    }

    @Test
    @DisplayName("providesPackageName=true means the packageName really is there")
    fun packageNameIsPopulatedWhenDeclared() = runTest {
        val adapter = adapter()
        if (!adapter.capabilities.providesPackageName || !adapter.capabilities.search) return@runTest
        val page = adapter.search(queryWithResults).expectSuccess("search")
        page.items.forEach {
            assertThat(it.packageName).isNotNull()
            assertThat(it.packageName).isNotEmpty()
        }
    }

    // --- Detail --------------------------------------------------------------------------------

    @Test
    @DisplayName("the detail of an existing ref comes back consistent with the ref requested")
    fun detailIsConsistentWithRequest() = runTest {
        val adapter = adapter()
        val detail = detailFor(existingRef).expectSuccess("detail of $existingRef")
        assertThat(detail.storeId).isEqualTo(adapter.id)
        assertThat(detail.ref).isEqualTo(existingRef)
        assertThat(detail.summary.title).isNotEmpty()
    }

    @Test
    @DisplayName("a non-existent ref gives NotFound, not an exception")
    fun missingRefIsNotFound() = runTest {
        val result = detailFor(missingRef)
        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    @Test
    @DisplayName("providesScreenshots=true means at least one listing has some")
    fun screenshotsArePopulatedWhenDeclared() = runTest {
        val adapter = adapter()
        if (!adapter.capabilities.providesScreenshots) return@runTest
        val detail = detailFor(existingRef).expectSuccess("detail")
        assertThat(detail.screenshots).isNotEmpty()
        detail.screenshots.forEach { assertThat(it.url).isNotEmpty() }
    }

    @Test
    @DisplayName("providesRating=false means no listing carries an invented rating")
    fun ratingIsAbsentWhenNotDeclared() = runTest {
        val adapter = adapter()
        if (adapter.capabilities.providesRating) return@runTest
        val detail = detailFor(existingRef).expectSuccess("detail")
        assertThat(detail.summary.rating).isNull()
        assertThat(detail.summary.ratingCount).isNull()
    }

    // --- Versions ------------------------------------------------------------------------------

    @Test
    @DisplayName("version history does what the capabilities declare")
    fun versionHistoryMatchesCapabilities() = runTest {
        val adapter = adapter()
        if (!adapter.capabilities.versionHistory) {
            // Declaring `false` and then answering means a capability that lies the other way
            // round: the UI would hide a tab that in fact works.
            assertThat(adapter.getVersions(existingRef)).isInstanceOf(StoreResult.Unsupported::class.java)
            return@runTest
        }
        // Versions can come from `getVersions` or from the detail: which of the two is not a
        // contract detail, as long as they are there.
        val versions = adapter.allVersionsForContract()
        assertThat(versions).isNotNull()
        assertThat(versions).isNotEmpty()
        versions?.forEach { assertThat(it.versionName).isNotEmpty() }
    }

    /**
     * `providesHash` mirrors how many hashes there really are — **wherever the store puts them**.
     *
     * The two sources are not equivalent, which is why both are examined. Normally the hash is on
     * the page, i.e. in the [AppVersion]s. an1 instead publishes it as CDN object metadata
     * (`x-amz-meta-checksum-sha256`), read with the `HEAD` already needed to resolve the download:
     * a hash published by the store to all intents and purposes, verified against the real bytes,
     * but appearing in no listing.
     *
     * `NONE` stays the strongest declaration, and this test is what makes it expensive to get
     * wrong: it is not enough for the versions to lack a hash, the download resolution must lack
     * one **too**. An adapter declaring `NONE` while filling `expectedSha256` would make the
     * verification card show a comparison the UI has already decided not to announce.
     */
    @Test
    @DisplayName("providesHash mirrors how many hashes there really are")
    fun hashAvailabilityIsHonest() = runTest {
        val adapter = adapter()
        val versions = adapter.allVersionsForContract() ?: return@runTest
        if (versions.isEmpty()) return@runTest
        val resolvedHash = (
            adapter.getDownloadLink(existingRef, existingVersionRef) as? StoreResult.Success
            )?.value?.let {
            when (it) {
                is DownloadResolution.Direct -> it.expectedSha256
                is DownloadResolution.UserAssisted -> it.expectedSha256
            }
        }
        when (adapter.capabilities.providesHash) {
            HashAvailability.ALWAYS -> versions.forEach {
                assertThat(it.sha256).isNotNull()
            }
            HashAvailability.SOMETIMES ->
                assertThat(versions.any { it.sha256 != null } || resolvedHash != null).isTrue()
            HashAvailability.NONE -> {
                versions.forEach { assertThat(it.sha256).isNull() }
                assertThat(resolvedHash).isNull()
            }
        }
    }

    @Test
    @DisplayName("providesSignerFingerprint=true means the signature is on every version")
    fun signerIsPopulatedWhenDeclared() = runTest {
        val adapter = adapter()
        if (!adapter.capabilities.providesSignerFingerprint) return@runTest
        val versions = adapter.allVersionsForContract() ?: return@runTest
        assertThat(versions).isNotEmpty()
        versions.forEach { assertThat(it.signerSha256).isNotNull() }
    }

    @Test
    @DisplayName("supportsSplits=false means no version is a split container")
    fun artifactTypeMatchesSplitSupport() = runTest {
        val adapter = adapter()
        if (adapter.capabilities.supportsSplits) return@runTest
        val versions = adapter.allVersionsForContract() ?: return@runTest
        versions.forEach { assertThat(it.artifactType.isSingleApk).isTrue() }
    }

    // --- Download ------------------------------------------------------------------------------

    @Test
    @DisplayName("download resolution is of the kind downloadMode promises")
    fun downloadResolutionMatchesMode() = runTest {
        val adapter = adapter()
        val resolution = adapter.getDownloadLink(existingRef, existingVersionRef)
            .expectSuccess("getDownloadLink")
        when (adapter.capabilities.downloadMode) {
            DownloadMode.DIRECT -> {
                assertThat(resolution).isInstanceOf(DownloadResolution.Direct::class.java)
                val direct = resolution as DownloadResolution.Direct
                assertSecureUrl(direct.url, "download url")
                assertThat(direct.fileName).isNotEmpty()
            }
            DownloadMode.USER_ASSISTED_ONLY -> {
                assertThat(resolution).isInstanceOf(DownloadResolution.UserAssisted::class.java)
                assertSecureUrl((resolution as DownloadResolution.UserAssisted).pageUrl, "pageUrl")
            }
            DownloadMode.DIRECT_WITH_FALLBACK, DownloadMode.WEBVIEW_ASSISTED_SILENT -> Unit
        }
    }

    @Test
    @DisplayName("providesHash=ALWAYS means the download carries the expected hash too")
    fun directDownloadCarriesHashWhenAlwaysAvailable() = runTest {
        val adapter = adapter()
        if (adapter.capabilities.providesHash != HashAvailability.ALWAYS) return@runTest
        val resolution = adapter.getDownloadLink(existingRef, existingVersionRef)
            .expectSuccess("getDownloadLink")
        val direct = resolution as? DownloadResolution.Direct ?: return@runTest
        // Without this, step 2 of the pre-install pipeline has nothing to compare the SHA-256
        // computed during the download against.
        assertThat(direct.expectedSha256).isNotNull()
    }

    // --- Internal consistency ------------------------------------------------------------------

    @Test
    @DisplayName("searchSource=LOCAL_INDEX obliges the adapter to be able to supply the index")
    fun localIndexImpliesIndexedAdapter() {
        val adapter = adapter()
        if (adapter.capabilities.searchSource != SearchSource.LOCAL_INDEX) return
        assertThat(adapter).isInstanceOf(IndexedStoreAdapter::class.java)
    }

    @Test
    @DisplayName("trending and recent respect the capabilities")
    fun optionalListingsMatchCapabilities() = runTest {
        val adapter = adapter()
        if (!adapter.capabilities.trending) {
            assertThat(adapter.getTrending()).isInstanceOf(StoreResult.Unsupported::class.java)
        }
        if (!adapter.capabilities.recent) {
            assertThat(adapter.getRecent()).isInstanceOf(StoreResult.Unsupported::class.java)
        }
    }

    @Test
    @DisplayName("no method throws, not even with absurd input")
    fun noMethodThrows() = runTest {
        val adapter = adapter()
        val nonsense = StoreAppRef("../../etc/passwd?<script>&%00")
        // The contract is "return an error", not "return the right result": whatever comes back
        // is fine, as long as something does.
        adapter.search("' OR 1=1 --")
        adapter.search("")
        adapter.search(queryWithResults, SearchFilters.NONE, page = 9999)
        adapter.getAppDetails(nonsense)
        adapter.getVersions(nonsense)
        adapter.getDownloadLink(nonsense)
        adapter.getTrending(page = -1)
        adapter.getRecent(page = -1)
        adapter.healthCheck()
    }

    // --- Helpers -----------------------------------------------------------------------------

    /**
     * Every URL towards the store must be HTTPS.
     *
     * The exception is loopback, and it serves one purpose: letting a test double answer in the
     * clear. An adapter cannot exploit it by accident — none of the nine stores lives on
     * `localhost` — while without it the contract test would force every subtype to stand up a TLS
     * server with a self-signed certificate to prove things that have nothing to do with TLS.
     */
    protected fun assertSecureUrl(url: String, what: String) {
        val loopback = url.startsWith("http://localhost") ||
            url.startsWith("http://127.0.0.1") ||
            url.startsWith("http://[::1]")
        assertThat(url.startsWith("https://") || loopback)
            .isTrue()
        if (!loopback) assertThat(url).startsWith("https://")
        else assertThat(what).isNotEmpty()
    }

    private suspend fun StoreAdapter.allVersionsForContract(): List<AppVersion>? {
        (getVersions(existingRef) as? StoreResult.Success)?.let { return it.value }
        return (detailFor(existingRef) as? StoreResult.Success)?.value?.versions
    }

    private fun <T> StoreResult<T>.expectSuccess(what: String): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("$what should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("$what should have succeeded on the fixtures, gave Unsupported")
    }

    private companion object {

        /** The only filters decidable by looking at a [StoreListingSummary]. */
        val ROW_FILTERS = listOf(
            FilterCapability.CONTENT_KIND,
            FilterCapability.CATEGORY,
            FilterCapability.MIN_RATING,
        )

        fun FilterCapability.isPopulatedOn(row: StoreListingSummary): Boolean = when (this) {
            FilterCapability.CONTENT_KIND -> row.contentKind != ContentKind.UNKNOWN
            FilterCapability.CATEGORY -> row.categories.isNotEmpty()
            FilterCapability.MIN_RATING -> row.rating != null
            else -> false
        }
    }
}
