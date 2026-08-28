package com.multistore.tools.index

import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreResult
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Produces the **payload** of `index.json`. The signature is added by `tools/sign-config.sh`.
 *
 * ```sh
 * ./gradlew :tools:index:buildIndex --args="build/index-payload.json 2026-08-25T21:00:00Z [release.json]"
 * tools/sign-config.sh build/index-payload.json .secrets/parsers-ed25519.pem index.json
 * ```
 *
 * The third argument is optional and is the `selfUpdate` block: it is produced by whoever builds the
 * release, because they are the only one who knows the APK's `versionCode`, hash and size. It lives
 * in a file and not among the arguments because those four values are read from the artifact, and
 * copying them by hand onto a command line is the easiest way to publish the previous build's hash.
 *
 * The date is passed in from outside because the document must be **reproducible**: `generatedAt` is
 * the only field that would change on every run even with a static catalogue, and with an internal
 * clock one could not verify that two runs of the same code over the same pages produce the same
 * file. It is the same reason this project's tests inject the clock.
 *
 * What it does, in order:
 *
 * 1. asks every adapter declaring `trending` for its ranking, and every adapter declaring `recent`
 *    for its new releases;
 * 2. fuses the rankings with [Fusion] — deduplicating by app **within** each list before fusing;
 * 3. interleaves the new releases per store, so that the one with the longest feed does not occupy
 *    the section on its own;
 * 4. writes down which stores did not answer, with the reason.
 *
 * What it does NOT do, and these are decisions:
 *
 * **It does not query modyolo for new releases.** Its `/feed/`, measured, had 24 entries of which
 * **six were adult content — and all six filed under "Role Playing"**, that is outside the six
 * categories that store declares adult. `show_nsfw_content` filters the label, and on that surface
 * the label is not there: the setting would remove nothing. The Home is a surface the app chooses
 * **without anybody having asked for anything**, and on such a surface a source has to be measured
 * before being adopted, not after. The other five were measured and are clean: apkcombo 96 entries,
 * apkmirror 10, pdalife 100, uptodown 10 + 48, apkmody 12.
 *
 * The general rule, which goes beyond modyolo: **a filter that reads a label does not protect a
 * source that does not label.** The only defence on an unlabelled surface is choosing the source, and
 * choosing it by looking at what is actually on it.
 *
 * **It does not publish `identity[]`.** The plan allowed for it — `packageName` mappings between
 * stores — but none of the surfaces this pipeline reads publishes one, except apkcombo. A list of
 * identities built on normalised titles would be the same inference `IdentityMatcher` already makes
 * on the device, with the added defect of being **signed**: a wrong inference would become a fact the
 * app cannot contradict. The 0.85 threshold exists precisely so that a wrong merge is impossible by
 * construction.
 */
object BuildIndex {

    /** How many entries per section. Beyond that, the Home becomes a list nobody scrolls. */
    private const val POPULAR_LIMIT = 30
    private const val RECENT_LIMIT = 40

    private const val SCHEMA_VERSION = 1

    @JvmStatic
    fun main(args: Array<String>) {
        val output = File(args.getOrElse(0) { "index-payload.json" })
        val generatedAt = args.getOrNull(1)
        // Named but unreadable is an **error**, not an absent block. `takeIf { it.isFile }` used
        // to swallow a wrong path, and the result was a signed index with no `selfUpdate` in it:
        // every installation applies that document, nobody is ever offered the update, and nothing
        // anywhere says why. It cost exactly one release to find out. Paths are relative to the
        // repository root — see `workingDir` in this module's build file.
        val selfUpdate = args.getOrNull(2)?.let { path ->
            val file = File(path)
            require(file.isFile) { "selfUpdate file does not exist: ${file.absolutePath}" }
            Json.parseToJsonElement(file.readText()) as? JsonObject
                ?: error("selfUpdate file is not a JSON object: ${file.absolutePath}")
        }

        val environment = NetworkEnvironment(cacheDirectory = File(output.parentFile ?: File("."), "http-cache"))
        val clients = StoreHttpClients(environment)
        try {
            val adapters = Adapters.all(clients)
            val document = runBlocking { collect(adapters, generatedAt, selfUpdate) }
            output.parentFile?.mkdirs()
            output.writeText(PRETTY.encodeToString(JsonObject.serializer(), document))
            System.err.println("written: ${output.absolutePath} (${output.length()} bytes)")
        } finally {
            clients.shutdown()
        }
    }

    internal suspend fun collect(
        adapters: List<StoreAdapter>,
        generatedAt: String?,
        selfUpdate: JsonObject? = null,
    ): JsonObject {
        val failures = LinkedHashMap<StoreId, String>()

        val rankings = adapters
            .filter { it.capabilities.trending }
            .mapNotNull { adapter -> adapter.itemsOf(failures) { it.getTrending() } }

        val feeds = adapters
            .filter { it.capabilities.recent && it.id !in EXCLUDED_FROM_RECENT }
            .mapNotNull { adapter -> adapter.itemsOf(failures) { it.getRecent() } }

        val popular = Fusion.fuse(rankings).take(POPULAR_LIMIT)
        val recent = interleave(feeds).take(RECENT_LIMIT)

        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            generatedAt?.let { put("generatedAt", it) }
            put("popular", JsonArray(popular.map { entryOf(it.app, sources = it.sources) }))
            put("recent", JsonArray(recent.map { entryOf(it, sources = 1) }))
            put(
                "stores",
                buildJsonArray {
                    // Only what did **not** answer: a list of nine rows saying "all fine" would be
                    // noise in a document every installation downloads.
                    failures.forEach { (storeId, detail) ->
                        add(
                            buildJsonObject {
                                put("store", storeId.wireName)
                                put("reachable", false)
                                put("detail", detail)
                            },
                        )
                    }
                },
            )
            // The block is passed **as is**, without being reinterpreted: whoever produces it reads
            // it from the APK, and rewriting it here would be a second chance to get the hash wrong.
            // The document is signed in full anyway.
            selfUpdate?.let { put("selfUpdate", it) }
        }
    }

    /**
     * The three stores' new releases, **one each per round**.
     *
     * Without this, the section would be pdalife in its entirety: its feed has a hundred entries and
     * apkmirror's has ten, and a `flatten` ordered by date would still give whoever publishes most
     * the most visible part. Ordering by date is also wrong for another reason: the three feeds'
     * dates are not comparable — apkcombo publishes the file's update time, pdalife the listing's.
     */
    internal fun interleave(feeds: List<List<StoreListingSummary>>): List<StoreListingSummary> {
        val cursors = feeds.map { it.iterator() }
        val result = mutableListOf<StoreListingSummary>()
        while (cursors.any { it.hasNext() }) {
            cursors.forEach { if (it.hasNext()) result += it.next() }
        }
        return result
    }

    private suspend fun StoreAdapter.itemsOf(
        failures: MutableMap<StoreId, String>,
        call: suspend (StoreAdapter) -> StoreResult<PagedResult<StoreListingSummary>>,
    ): List<StoreListingSummary>? = when (val result = call(this)) {
        is StoreResult.Success -> result.value.items.takeIf { it.isNotEmpty() }
            ?: null.also { failures[id] = "empty" }

        is StoreResult.Failure -> null.also { failures[id] = result.error.describe() }

        // Not a fault: the adapter does not declare that capability. It does not end up among the
        // failures, because "does not answer" and "does not do it" are two different sentences.
        StoreResult.Unsupported -> null
    }

    private fun entryOf(app: StoreListingSummary, sources: Int): JsonObject = buildJsonObject {
        put("store", app.storeId.wireName)
        put("ref", app.ref.value)
        put("title", app.title)
        app.packageName?.let { put("packageName", it) }
        app.developer?.let { put("developer", it) }
        app.iconUrl?.let { put("iconUrl", it) }
        app.latestVersionName?.let { put("version", it) }
        app.lastUpdated?.let { put("updatedAt", it.toString()) }
        if (sources > 1) put("sources", sources)
    }

    /**
     * The stores excluded from new releases, **with the reason in the code and not elsewhere**.
     *
     * See the note at the head of the class. A `Set` and not an `if` because the next exclusion, if
     * there is one, will have to be a line next to this one — with its measurement.
     */
    private val EXCLUDED_FROM_RECENT = setOf(StoreId.MODYOLO)

    private val PRETTY = Json { prettyPrint = true; encodeDefaults = true }
}

private fun com.multistore.store.api.StoreError.describe(): String = when (this) {
    is com.multistore.store.api.StoreError.Blocked -> "blocked:${kind.name.lowercase()}"
    is com.multistore.store.api.StoreError.RateLimited -> "rate-limited"
    is com.multistore.store.api.StoreError.ParseFailure -> "parse:$selector"
    is com.multistore.store.api.StoreError.Network -> "network:${httpCode ?: "io"}"
    com.multistore.store.api.StoreError.NotFound -> "not-found"
    else -> "error"
}
