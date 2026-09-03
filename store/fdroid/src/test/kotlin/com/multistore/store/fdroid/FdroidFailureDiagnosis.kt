package com.multistore.store.fdroid

import com.multistore.store.api.StoreError
import com.multistore.store.fdroid.index.FdroidIndexClient

/**
 * Which of f-droid's failures happened — the branching this canary's `orFail` claimed to do and did
 * not.
 *
 * ### The comment that asserted the opposite of the code
 *
 * What stood there was one line for every failure:
 *
 * ```kotlin
 * // The message separates the three cases that lead to three different jobs, and it is the
 * // first line whoever opens the issue will read: see `.github/workflows/canary.yml`.
 * is StoreResult.Failure -> throw AssertionError("$what: F-Droid answered $error")
 * ```
 *
 * The comment is a promise; the code beneath it is `toString()`. It was the only one of the nine
 * canaries that did not branch, and — this is the part that made it expensive — the only one
 * talking to **two hosts**.
 *
 * ### Why two hosts make it worse than elsewhere
 *
 * The index lives on `f-droid.org`; the fallback search lives on `search.f-droid.org`, which is a
 * separate machine, self-hosted, with no CDN, no mirror and **no fallback in [FdroidConfig]**.
 * Three of the six checks depend on it alone. So "the search host had a bad night" and "the pinned
 * certificate has rotated" printed the same sentence — and `canary.yml`'s issue body then supplied
 * its default reading, *update `<Store>Selectors` and recapture the fixture*, on the one store of
 * the nine that has **no selectors and no page fixtures at all**. Every path led somewhere wrong.
 *
 * ### The pin is not a markup change, and it is the most serious thing here
 *
 * A rotated or substituted signing certificate arrives as a `ParseFailure` whose selector is
 * **`entry.jar/signer`** — `SELECTOR_SIGNER`, the `WrongSigner` result — and the snippet even
 * carries `expected=… found=…`. Read as "a selector stopped matching" it would send somebody to
 * edit a CSS selector that does not exist. It has to be read as: **the index is being discarded,
 * and with it search, detail, updates and categories for the whole store**, and the pin must not
 * be widened until the rotation is confirmed outside this channel.
 *
 * Note the neighbouring selector is a **different** claim and it would be easy to conflate them,
 * because the names nearly match: `entry.jar/signature` (`SELECTOR_SIGNATURE`) is `Tampered` or
 * `Unsigned`, i.e. the signature does not verify at all and no certificate was ever compared. Both
 * are serious; only the first is about the pin, and only the first raises the question of whether
 * to trust a new key.
 *
 * That is why the diagnosis is a function with an offline test rather than four lines inside the
 * canary: on a healthy night none of these branches runs, so a green canary never exercises any of
 * them, and the one that matters most is the one that has never printed.
 */
internal enum class FdroidHost(val label: String) {
    /** `f-droid.org`: the signed index and the artefacts. */
    REPO(FdroidConfig.HOST),

    /**
     * `search.f-droid.org`: a separate machine with no mirror and no fallback.
     *
     * It serves one window — the first launch, before the 57 MB sync finishes — so it can fall over
     * on its own for days without anyone noticing, which is precisely why the canary asks.
     */
    SEARCH("search.f-droid.org"),
}

/**
 * The message for a failure, naming the job rather than the `toString()`.
 *
 * [host] is passed by the call site and has no default **on purpose**: three of the six checks talk
 * to a different machine from the other three, and a default would silently attribute a search
 * outage to the repository. The compiler now refuses a call that has not decided.
 */
internal fun fdroidFailureMessage(what: String, host: FdroidHost, error: StoreError): String =
    "$what: " + when (error) {
        is StoreError.ParseFailure -> when (error.selector) {
            FdroidIndexClient.SELECTOR_SIGNER ->
                "**the index signature is not the pinned one.** This is the most serious thing " +
                    "this canary can say and it is **not** fixed by updating a selector: the " +
                    "certificate `entry.jar` is signed with is no longer " +
                    "`FdroidConfig.signerFingerprint`. The snippet carries both values " +
                    "(`${error.snippetHash}`). Either F-Droid rotated the key — to be confirmed " +
                    "**outside this channel**, on their announcement, not by trusting the new " +
                    "document — or a mirror is serving something that did not come from them. " +
                    "Until that is settled the pin is **not** widened: a discarded index is a " +
                    "stale catalogue, a widened pin is every installation trusting whoever " +
                    "signed. Note what is already happening meanwhile: with the index discarded, " +
                    "search, detail, updates and categories are gone for the whole store."

            FdroidIndexClient.SELECTOR_ENTRY_HASH, FdroidIndexClient.SELECTOR_INDEX_HASH ->
                "**a published hash does not match the bytes delivered** " +
                    "(`${error.selector}`, ${error.snippetHash}). The signature verified, so this " +
                    "is not the pin and not a key rotation: the document is signed by F-Droid and " +
                    "the mirror handed us different bytes than it promised. It concerns **one " +
                    "mirror**, so retry — and if it persists, that is worth reporting to them " +
                    "rather than changing anything here."

            FdroidIndexClient.SELECTOR_SIGNATURE ->
                "**`entry.json` is not validly signed** (${error.snippetHash}). Note this is a " +
                    "different claim from the pin: the certificate was not compared, because the " +
                    "signature itself does not verify or is absent. A tampered or truncated jar " +
                    "from a mirror looks like this, so retry from another mirror first — but if " +
                    "it reproduces, treat it with the same seriousness as a wrong signer and do " +
                    "**not** relax anything to get the index to load."

            FdroidIndexClient.SELECTOR_ENTRY_JAR ->
                "**`entry.jar` could not be read as a signed jar at all** " +
                    "(${error.snippetHash}). Not a rotation and not a hash: the container is " +
                    "wrong. The likely causes are a truncated download or a mirror serving an " +
                    "error page with a 200, so retry first."

            else ->
                "**the index document changed shape**: `${error.selector}` " +
                    "(${error.snippetHash}). F-Droid versions `index-v2` on its own terms, so " +
                    "this is the projection needing to learn the new schema — " +
                    "`PackageProjection` — and **not** a CSS selector: this store has none, and " +
                    "no page fixtures either. Ignore the \"recapture the fixture\" line in the " +
                    "issue body; it does not apply to f-droid."
        }

        is StoreError.Blocked ->
            "**${host.label} is blocking us** (${error.kind}). This would be new: both hosts are " +
                "plain nginx with no bot management and no `cf-*` headers, which is also why " +
                "nothing here is ever skipped the way uptodown's canary skips. Check the " +
                "User-Agent — `FdroidConfig.DEFAULT_USER_AGENT` names this project honestly — and " +
                "reassess the tier in the store table before touching anything else."

        is StoreError.RateLimited ->
            "**${host.label} is rate-limiting us** (429" +
                (error.retryAfter?.let { ", retry in $it" } ?: "") +
                "). Not a fault and not a schema change. Look at `permitsPerSecond` — and note " +
                "that this canary downloads the whole index once per class, on purpose, so if " +
                "this is the repo host the cost is one document a night and the limit is more " +
                "likely to be about the mirror than about us."

        StoreError.NotFound ->
            "**404 on ${host.label}.** " + when (host) {
                FdroidHost.SEARCH ->
                    "The fallback search API has moved or been retired — " +
                        "`FdroidConfig.DEFAULT_SEARCH_API_URL`. It is a **separate host** from " +
                        "the repository with no mirror and no fallback, so this says nothing " +
                        "about the index: the catalogue and every installed app keep working, " +
                        "and what is lost is the first-launch window before the sync finishes. " +
                        "That is also why nobody would notice without this check."

                FdroidHost.REPO ->
                    "An address under the repository is gone. If it is the artefact, the mirror " +
                        "has dropped a file the index still lists — retry, then look at the " +
                        "repo path (`FdroidConfig.DEFAULT_REPO_PATH`); if it is `entry.jar` " +
                        "itself, the repository layout has changed, which is a much larger claim."
            }

        is StoreError.Unsupported ->
            "**the adapter now declares this unsupported** (${error.what}). That is our code " +
                "having changed, not F-Droid."

        is StoreError.Network ->
            "**${host.label} did not answer** " +
                "(${error.httpCode?.let { "HTTP $it" } ?: error.cause?.javaClass?.simpleName ?: "no cause"})" +
                ". Not a schema change and not the pin. " + when (host) {
                    FdroidHost.SEARCH ->
                        "This is the **search host**, which is a separate self-hosted machine " +
                            "with no CDN, no mirror and no fallback — it falls over on its own, " +
                            "and the index is untouched. Retry before concluding anything; if it " +
                            "stays down, the only user-visible loss is search on a device that " +
                            "has not synced yet."

                    FdroidHost.REPO ->
                        "This is the **repository host**. The index is 57 MB from a public " +
                            "mirror, so a timeout here is often the mirror and not the store. " +
                            "Retry by hand before changing anything."
                }

        is StoreError.Unexpected ->
            "**an exception escaped the adapter** (${error.cause?.javaClass?.simpleName}). No " +
                "method of `StoreAdapter` is allowed to do that, so this one is ours regardless " +
                "of what F-Droid did."
    }
