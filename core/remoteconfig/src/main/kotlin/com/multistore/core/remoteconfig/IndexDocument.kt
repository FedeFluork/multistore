package com.multistore.core.remoteconfig

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The index that fills the Home screen, signed with the same key as `parsers.json`.
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "generatedAt": "2026-08-25T21:00:00Z",
 *   "popular": [ { "store": "uptodown", "ref": "capcut", "title": "CapCut", "sources": 2 } ],
 *   "recent":  [ { "store": "apkcombo", "ref": "…/gt.recovery.reboot", "title": "Recovery Reboot" } ],
 *   "stores":  [ { "store": "an1", "reachable": false, "detail": "403" } ],
 *   "selfUpdate": { "versionCode": 2, "versionName": "0.2.0", "url": "…", "sha256": "…" }
 * }
 * ```
 *
 * ### Why it is a separate document from `parsers.json`, and not a section of it
 *
 * Because the two have **opposite life cycles**. `parsers.json` changes when a store changes markup
 * — weeks, months — and holds for the whole life of the process, because the adapters receive it
 * from their constructor. The index changes every time the pipeline runs, and has to be able to
 * appear **without restarting the app**: it is content, not configuration.
 *
 * Keeping them together would have forced a choice: either the index becomes immutable per process —
 * and the Home stays empty until the restart following the first sync — or the selectors become
 * observable, and an adapter can interpret with one configuration a page downloaded with another.
 * Two files, two policies, no compromise.
 *
 * ### The document can be missing, and the app does not notice
 *
 * The non-negotiable rule about remote config holds here: the compiled defaults always exist. Here
 * the "compiled default" is **the absence of the two sections**: the Home goes on showing the local
 * index's state, F-Droid's recent updates, the categories and the updates, and the two new sections
 * simply do not appear. A CDN being down does not produce an empty screen.
 */
@Serializable
data class IndexDocument(
    val schemaVersion: Int = 0,
    /**
     * When the pipeline produced it, ISO-8601.
     *
     * A string and not an `Instant`, as in [ParsersDocument] and for the same reason: it is a field
     * that is **shown**, not one decisions are made on, and a badly written date must not cause an
     * otherwise valid document to be discarded.
     */
    val generatedAt: String? = null,
    val popular: List<IndexEntry> = emptyList(),
    val recent: List<IndexEntry> = emptyList(),
    val stores: List<IndexStoreState> = emptyList(),
    val selfUpdate: SelfUpdateRelease? = null,
) {
    companion object {
        /**
         * The schema this version of the app can read.
         *
         * A document with a higher number is **discarded** rather than read as best it can: a new
         * schema can change the meaning of an existing field, and showing half an index would be
         * worse than showing none.
         */
        const val SUPPORTED_SCHEMA: Int = 1

        const val FILE_NAME: String = "index.json"
    }
}

/**
 * An app as the pipeline saw it on a store.
 *
 * It is deliberately poor, and echoes `StoreListingSummary` without being it: `:core:remoteconfig`
 * does not depend on `:core:model` for domain types, and a serialised document must not follow the
 * renames of a domain data class. The translation is done by `:core:data`, which sees both.
 *
 * [store] is the `StoreId.wireName`, not the Kotlin constant's name: renaming a constant must not
 * invalidate an already published document. A store this version of the app does not know drops
 * **that entry**, not the document.
 */
@Serializable
data class IndexEntry(
    val store: String = "",
    val ref: String = "",
    val title: String = "",
    val packageName: String? = null,
    val developer: String? = null,
    val iconUrl: String? = null,
    val version: String? = null,
    /**
     * How many independent charts it appears in, for `popular` entries only.
     *
     * It is not decoration: it is the only part of the RRF score that survives into the document,
     * and it serves to justify the ordering to whoever reads the file. Measured on 25/08/2026 across
     * three charts and 27 distinct apps, **three** apps appear more than once — CapCut, Spotify,
     * YouTube. For all the others it is `1`, and the order is the interleaving of the three lists.
     */
    val sources: Int = 1,
    /** ISO-8601, and absent when the source does not publish it or publishes it in the future. */
    val updatedAt: String? = null,
)

/**
 * What the pipeline saw when it queried a store.
 *
 * It serves something the app cannot know by itself: **that a store is broken before trying**. The
 * local circuit breaker learns the same thing, but learns it by failing — one failed request per
 * user, multiplied by every user.
 *
 * It switches nothing off by itself, and must not: it is an observation made from another network,
 * at another time, and a store unreachable from CI may answer perfectly well from the reader's
 * phone. The Home screen uses it to **say**, not to decide.
 */
@Serializable
data class IndexStoreState(
    val store: String = "",
    val reachable: Boolean = true,
    /** What happened, in readable form: `403`, `timeout`, `parse`. For diagnostics. */
    val detail: String? = null,
)

/**
 * MultiStore's own update.
 *
 * ### Why it goes through here and not through a store
 *
 * MultiStore is on no store, so there was no distribution channel. The channel now exists and is
 * this document — the same one the Home screen already downloads, signed with the same key, verified
 * with the same code. It is not a second channel: it is one extra field on the one that exists.
 *
 * ### What does NOT change: the verification pipeline
 *
 * MultiStore's APK goes through the same seven steps as any other: size, streaming SHA-256, reading
 * with `apksig`, **`packageName` match** — which here is ours — and comparison of the signer with
 * the **installed** one, read from the `PackageManager`. That last one is the check that counts: an
 * `index.json` signed with our Ed25519 key could still point at an APK signed by somebody else, and
 * in that case installation must be refused. The two signatures protect different things — one the
 * document, the other the package — and the second is not substitutable by the first.
 *
 * [sha256] is not optional in practice even though the type allows it: without it, the only thing
 * the app knows about the file is that a signed document declared its address.
 */
@Serializable
data class SelfUpdateRelease(
    val versionCode: Long = 0,
    val versionName: String = "",
    @SerialName("minSdk")
    val minSdk: Int = 0,
    val url: String = "",
    val sha256: String? = null,
    val size: Long? = null,
    /** This version's notes, already in the language the pipeline publishes them in. */
    val notes: String? = null,
)
