package com.multistore.store.modyolo

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The **real** modyolo, not the fixtures. Runs only in the nightly canary.
 *
 * Unit tests never touch the network; this is the module's only one that does, and it is excluded
 * from `test`. Run it with `./gradlew :store:modyolo:canaryTest`.
 *
 * On this store the canary has one extra job: **watching the adult category ids**. They are
 * WordPress numbers, and modyolo has already added five alongside the first. If the filter stopped
 * removing anything, the setting would look enabled and do nothing — the worst way a feature of
 * this kind can break.
 */
@Tag("canary")
@DisplayName("Canary — modyolo (real network)")
class ModyoloCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var modyolo: ModyoloStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("modyolo-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        modyolo = ModyoloStoreAdapter(config = ModyoloConfig(), clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still returns results with icons`() = runTest {
        val page = modyolo.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        assertThat(page.items.first().title).isNotEmpty()
        // The icon depends on `_embed=wp:featuredmedia` **and** on `_links` inside `_fields`: if
        // WordPress changed that contract, every icon would become null with no error at all, and
        // the results would stay grey.
        assertThat(page.items.mapNotNull { it.iconUrl }).isNotEmpty()
    }

    @Test
    fun `the adult-content filter still removes something`() = runTest {
        val all = modyolo.search(NSFW_QUERY, SearchFilters(includeNsfw = true)).orFail("unfiltered search")
        val filtered = modyolo.search(NSFW_QUERY, SearchFilters.NONE).orFail("filtered search")

        assertThat(all.items).isNotEmpty()
        // If the two sets came back equal, either `categories_exclude` is no longer honoured **or**
        // the category ids have changed. Either way the "Show NSFW content" setting has become
        // decorative, and `ModyoloConfig.nsfwCategoryIds` needs updating (or a `parsers.json`
        // published that does it).
        assertThat(filtered.items.size).isLessThan(all.items.size)
    }

    @Test
    fun `the listing still exposes the package and the versions`() = runTest {
        val detail = modyolo.getAppDetails(StoreAppRef(APP_REF)).orFail("detail")

        assertThat(detail.summary.title).isNotEmpty()
        // The package is deduced from the Google Play link, and it is the only pre-install check
        // that can say no on this store: no hash, no original signature. If
        // `original_download_url` disappeared, verification would silently fall back to "not
        // contradicted".
        assertThat(detail.summary.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(detail.versions).isNotEmpty()
        assertThat(detail.versions.map { it.ref }.toSet()).hasSize(detail.versions.size)
    }

    @Test
    fun `the download still goes through the AJAX call and the file still exists`() = runTest {
        val resolution = modyolo.getDownloadLink(StoreAppRef(APP_REF)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("modyolo declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        // No raw spaces and no double escaping: this is the conditional normalisation, and it is
        // what separated "a quarter of the binaries are dead" from "twenty-eight out of forty
        // looked dead".
        assertThat(direct.url).doesNotContain(" ")
        assertThat(direct.url).doesNotContain("%25")

        // The preflight on a **recent** app must say yes. If it said no right here it would not be
        // "half the catalogue is dead": it would be the AJAX endpoint having stopped giving the
        // right URL, or the CDN being down.
        assertThat(modyolo.preflight(direct).orFail("preflight")).isTrue()
    }

    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the schema or the markup has changed**. No match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). If the selector names " +
                    "`wp/v2` or `v1/posts` the API has changed; if it names `a.download` the " +
                    "theme has. `ModyoloSelectors` or the JSON models need updating, and the " +
                    "matching fixture recapturing.",
            )
            is StoreError.Blocked -> error(
                "$what: **modyolo is blocking us** (${e.kind}). This had never happened — it " +
                    "answers identically to any User-Agent, including none. Check whether " +
                    "Cloudflare has appeared in active mode and reassess its tier and risk in " +
                    "the store table.",
            )
            is StoreError.RateLimited -> error(
                "$what: **modyolo is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a schema change: before touching the adapter, look at " +
                    "`permitsPerSecond`.",
            )
            StoreError.NotFound -> error(
                "$what: **NotFound**. On modyolo that means `data: null` with HTTP 200, that is " +
                    "the post `$APP_REF` is gone. Not an adapter fault: pick another reference " +
                    "app for the canary.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        const val QUERY = "minecraft"
        const val NSFW_QUERY = "lewd"
        const val APP_REF = "minecraft-19"
        const val PACKAGE_NAME = "com.mojang.minecraftpe"
    }
}
