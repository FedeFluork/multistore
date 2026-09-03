package com.multistore.store.modyolo

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.PageFetcher
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
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
    private lateinit var config: ModyoloConfig
    private lateinit var modyolo: ModyoloStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("modyolo-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        config = ModyoloConfig()
        modyolo = ModyoloStoreAdapter(config = config, clients = clients)
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

    /**
     * The adult-content filter still removes something.
     *
     * **It compares the sets and not their sizes**, and the difference is not cosmetic. The
     * exclusion is server-side — `categories_exclude` on the WordPress API — so it is applied
     * before pagination, and comparing two page *sizes* silently assumes two facts that belong to
     * modyolo and not to us: that at least one of the top twenty matches for the query sits in a
     * labelled category, and that what is left after the exclusion still fits inside one page.
     * Measured 03/09/2026: 16 results unfiltered, 4 filtered, so there are 16 rows of headroom
     * today. The day this query matched more than a page's worth of non-adult posts, both pages
     * would saturate at the same count and the canary would announce that the NSFW setting had
     * "become decorative" while `categories_exclude` was being honoured exactly.
     *
     * The set difference has no ceiling to hit, and it is also the claim the comment always wanted
     * to make: not "fewer came back" but "these specific posts were removed".
     */
    @Test
    fun `the adult-content filter still removes something`() = runTest {
        val all = modyolo.search(NSFW_QUERY, SearchFilters(includeNsfw = true)).orFail("unfiltered search")
        val filtered = modyolo.search(NSFW_QUERY, SearchFilters.NONE).orFail("filtered search")

        assertThat(all.items).isNotEmpty()
        // If nothing was removed, either `categories_exclude` is no longer honoured **or** the
        // category ids have changed. Either way the "Show NSFW content" setting has become
        // decorative, and `ModyoloConfig.nsfwCategoryIds` needs updating (or a `parsers.json`
        // published that does it). Measured 03/09/2026: 12 of the 16 refs were removed.
        val removed = all.items.map { it.ref }.toSet() - filtered.items.map { it.ref }.toSet()
        assertThat(removed).isNotEmpty()
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

        // The preflight on a **recent** app should say yes, and when it does not, *which* no it is
        // decides whether there is anything to do. See [deadBinaryVerdict]: a 500 is this store's
        // measured signature for an object that has fallen off the CDN — roughly one binary in
        // four on the older layers — and no change here would bring it back, so the check skips
        // and says so. Anything else fails, naming the code: the `admin-ajax.php` endpoint handing
        // back a wrong URL looks identical from the outside, and that one is ours.
        //
        // Until 03/09/2026 both readings arrived as `expected: true / but was: false`, which is
        // the only red in this class that named neither a job nor a next step — on the single most
        // likely thing to be true about modyolo.
        if (!modyolo.preflight(direct).orFail("preflight")) {
            val probe = fetcher().head(direct.url)
            val code = (probe as? StoreResult.Success)?.value?.code
            val where = java.net.URI(direct.url).host
            when (deadBinaryVerdict(code)) {
                DeadBinaryVerdict.THEIR_DEAD_BINARY -> abort<Nothing>(
                    "preflight: **the premise expired, and this is not a fault.** `$where` " +
                        "answered $DEAD_BINARY_CODE for `${direct.fileName}`, which is modyolo's " +
                        "measured signature for an object that is no longer on the CDN — about " +
                        "one binary in four on the older layers, and the file name is built from " +
                        "the post's `lastest_version`, so a version bumped before the object " +
                        "lands produces exactly this. Nothing in this repository makes it true " +
                        "again: re-anchor `APP_REF` to a current post if it keeps happening. The " +
                        "run's step summary lists this check as skipped, and while it skips the " +
                        "AJAX resolution is going unverified.",
                )
                DeadBinaryVerdict.WORTH_FAILING -> error(
                    "preflight: **the file did not answer, and not in the way modyolo's dead " +
                        "objects do.** `$where` answered ${code ?: "nothing readable"} for " +
                        "`${direct.fileName}`, where a rotted object answers " +
                        "$DEAD_BINARY_CODE. So this is not their catalogue ageing: the likeliest " +
                        "cause is `admin-ajax.php` resolving to a URL that is no longer right — " +
                        "check `ModyoloConfig.AJAX_PATH` and the `k_get_download` payload, and " +
                        "that the `Referer` of the variant is still being sent. A 404 in " +
                        "particular means the path convention moved, not that the app is gone.",
                )
            }
        }
    }

    /**
     * The file URL is still **percent-encoded once**, and the reference still exercises that.
     *
     * This is the conditional normalisation in [com.multistore.store.common.html.Urls.normalizeFileUrl],
     * and it is what separated "a quarter of the binaries are dead" from "twenty-eight out of
     * forty looked dead" — modyolo's CDN mixes paths that are already escaped with paths carrying
     * raw spaces, and encoding an escaped one twice turns `%2B` into `%252B` and a live file into
     * a 404.
     *
     * **It has its own reference app, and that is the whole point of the test.** The two assertions
     * used to sit on the download test above, whose reference resolves to
     * `Minecraft_v1_26_50_27.apk` — no space, no percent, nothing to normalise — so neither line
     * could fail on any input the store was producing. Captions, in this repository's vocabulary:
     * present, green, and proving nothing. `game-booster-4x-faster-120446` resolves to a path
     * carrying **both** shapes — `Game%20Booster%204x%20Faster/…-%28Premium%29-modyolo.apk`,
     * measured through the adapter on 03/09/2026 — so here the assertions have something to bite
     * on.
     *
     * The premise is checked first and separately, because an anchor like this expires: if that
     * post ever resolves to a plain name the test says so and skips, instead of going quietly back
     * to being a caption.
     */
    @Test
    fun `the file URL is still escaped exactly once`() = runTest {
        val resolution = modyolo.getDownloadLink(StoreAppRef(ESCAPED_REF)).orFail("escaped download")
        val direct = resolution as? DownloadResolution.Direct
            ?: error("modyolo declares DIRECT but returned ${resolution::class.simpleName}")

        if (!direct.url.contains('%')) {
            abort<Nothing>(
                "escaped download: **the premise expired, and nothing is broken.** " +
                    "`$ESCAPED_REF` was chosen because its path carries `%20` and `%28`, which " +
                    "is what makes the two assertions below able to fail at all. It now resolves " +
                    "to `${direct.fileName}`, with nothing to escape. Re-anchor `ESCAPED_REF` to " +
                    "a post whose file name has a space or a bracket — they are common on this " +
                    "store — or these lines go back to being decoration.",
            )
        }
        // A raw space means we did not encode a path that needed it; a `%25` means we encoded one
        // that was already encoded. The two failures are opposite mistakes and the same symptom
        // for the user: a 404 on a file that is really there.
        assertThat(direct.url).doesNotContain(" ")
        assertThat(direct.url).doesNotContain("%25")
    }

    private suspend fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
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
            // The one branch that cannot be read off the error alone, and the one whose old
            // wording did the most damage: it asserted both a mechanism and a conclusion — "the
            // post is gone. Not an adapter fault" — and so closed the question before it was
            // asked. Where uptodown's message wrongly blamed us, this one wrongly exonerated us,
            // and three of the four checks that share it are searches that never mention a post
            // at all. See [modyoloNotFoundMessage] for what really answers 404 here.
            StoreError.NotFound -> {
                // Asked twice, and the second time only where it changes the outcome: a single
                // unretried request is one bad edge node away from turning a renamed REST path
                // into a silent skip, and nothing else retries it — `NotFound` is not a challenge,
                // so the escalation ladder walks no rung for it. The message is built from the
                // decisive answer, so the words and the outcome cannot disagree.
                val first = modyolo.healthCheck()
                val decisive = if (modyoloIsUnreachable(first)) modyolo.healthCheck() else first
                val message = modyoloNotFoundMessage(what, decisive)
                if (modyoloIsUnreachable(decisive)) abort(message) else error(message)
            }
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    /**
     * The adapter's own client, for the one diagnostic request the adapter does not expose.
     *
     * It is built from [ModyoloConfig]'s own User-Agent and rate limit rather than from a fresh
     * OkHttp client, because a canary that measured with a second client would be measuring that
     * client — the rule this project paid the most for.
     */
    private fun fetcher(): PageFetcher = PageFetcher(
        clients.forStore(
            StoreId.MODYOLO,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private companion object {
        const val QUERY = "minecraft"
        const val NSFW_QUERY = "lewd"
        const val APP_REF = "minecraft-19"
        const val PACKAGE_NAME = "com.mojang.minecraftpe"

        /**
         * A post whose file name carries `%20` and `%28`: see the escaping test.
         *
         * Deliberately a **second** reference. The main one resolves to a plain
         * `Minecraft_v1_26_50_27.apk`, on which an encoding check cannot fail.
         */
        const val ESCAPED_REF = "game-booster-4x-faster-120446"
    }
}
