package com.multistore.core.remoteconfig

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The document that repairs an adapter without a release.
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "generatedAt": "2026-08-25T09:12:00Z",
 *   "stores": {
 *     "uptodown": { "selectors": { "searchItem": "#content-list .card" } },
 *     "apkmirror": { "baseUrl": "https://www.apkmirror.com" }
 *   }
 * }
 * ```
 *
 * Every value of `stores` is a **partial override** of that store's compiled configuration: what is
 * not named stays as it is. It is not a writing convenience, it is the non-negotiable rule seen from
 * the format's side — the remote config is an override, never the only source — because a document
 * that had to repeat the whole configuration would also be a document that, by forgetting a field,
 * zeroes it.
 *
 * The keys of `stores` are the [com.multistore.core.model.StoreId.wireName]s, not the Kotlin
 * constants' names: renaming a constant must not invalidate an already published document.
 */
@Serializable
data class ParsersDocument(
    val schemaVersion: Int = 0,
    /**
     * When the pipeline produced it, ISO-8601.
     *
     * A string and not an `Instant`: it is a field the app **shows**, not one it decides on, and
     * keeping it as such stops a badly written date causing an otherwise perfectly valid document to
     * be discarded. The conversion, where needed, is `Instant.parse` with `getOrNull`.
     */
    val generatedAt: String? = null,
    val stores: Map<String, JsonObject> = emptyMap(),
    /**
     * The list of hosts the assisted-download WebView must not load.
     *
     * ### Why here and not in a third document
     *
     * Because it is the same thing this document already does: **repairing without a release**. An
     * advertising list ages like a CSS selector — a new network, a changed domain — and the
     * machinery needed is identical: same envelope, same signature, same six-hour window, same
     * `block_remote_parsers` switch. A document of its own would have wanted a URL, a fetcher, a
     * store, a `SignedDocumentSink` and a second row in Settings, to say the same thing at a
     * different hour of the day.
     *
     * Unlike `stores`, it is not per store: it is **one** partial override of `WebFilterConfig`.
     * Naming only `blockedHosts` leaves the exceptions on the compiled defaults, which is exactly
     * what is wanted when adding a network without touching Cloudflare.
     */
    val webFilter: JsonObject? = null,
) {
    companion object {
        /**
         * The schema this version of the app can read.
         *
         * A document with a higher `schemaVersion` is **discarded**, not read as best it can: a new
         * schema can change the meaning of an existing field, and applying half of it would be worse
         * than staying on the compiled defaults.
         */
        const val SUPPORTED_SCHEMA: Int = 1
    }
}

/**
 * The public key `parsers.json` is verified with, pinned.
 *
 * ### What it protects, and what it does not
 *
 * It protects against anyone being able to tell the app where to look for selectors and which
 * domains to query. A document signed with another key is discarded before being read, so a
 * compromised CDN, a hijacked DNS or a proxy in the middle cannot change the adapters' behaviour: at
 * most they can prevent the configuration being updated, and in that case the compiled defaults
 * apply.
 *
 * It does not protect against whoever has the private key. That does not live in the repository —
 * see `.gitignore` — and lives where the pipeline lives.
 */
object ParsersKey {

    /**
     * Ed25519, 32 raw bytes.
     *
     * Generated on 25/08/2026 with `openssl genpkey -algorithm ed25519`. The raw form is the last 32
     * bytes of the DER `SubjectPublicKeyInfo`, which for Ed25519 is 44 long:
     *
     * ```
     * openssl pkey -in key.pem -pubout -outform DER | tail -c 32 | base64
     * ```
     *
     * **This is the pipeline's key.** Replacing it is a breaking change: already published documents
     * stop verifying, and installations that do not yet have the update fall back to the compiled
     * defaults — which is the safe behaviour, but has to be known beforehand and not after.
     */
    const val PUBLIC_BASE64: String = "IfxPeHqR7LfK5WCvQB6L8RpI2zsfilryUyoJ9dYAkV0="

    val PUBLIC: ByteArray get() = Base64.getDecoder().decode(PUBLIC_BASE64)

    /**
     * Where the app goes looking for the document.
     *
     * An address that does not answer produces exactly the designed behaviour — no document
     * accepted, compiled defaults, and the Settings screen saying so. The channel is switched on by
     * publishing the first document, not by modifying the app.
     *
     * **It cannot be changed afterwards.** An installed copy asks this address forever, and the only
     * way to move it is an update — which has to arrive through the address being moved. Changing
     * the GitHub account or the repository name has the same effect as deleting the host.
     */
    const val PARSERS_URL: String = "https://fedefluork.github.io/multistore/v1/parsers.json"

    /**
     * Where the Home screen's index is, and the **self-update**.
     *
     * Same host and same key as [PARSERS_URL], different document: see `IndexDocument` for why it is
     * not a section of `parsers.json`. The same note as above applies — an address that does not
     * answer produces exactly the designed behaviour: no index, the Home without the two sections,
     * and everything else unchanged.
     */
    const val INDEX_URL: String = "https://fedefluork.github.io/multistore/v1/index.json"
}
