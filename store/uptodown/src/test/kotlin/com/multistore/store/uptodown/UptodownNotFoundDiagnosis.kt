package com.multistore.store.uptodown

import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult

/**
 * Which job a 404 from uptodown actually is — decided by what the **language root** answered, and
 * not by the 404 alone.
 *
 * ### Why this is a function and not four lines inside the canary
 *
 * On 31/08/2026 the nightly canary reported all five uptodown checks red with one sentence:
 * *"NotFound. On this store that nearly always means the URL scheme has changed."* The scheme had
 * not changed. Minutes later the **same adapter**, the same User-Agent and the same URLs answered
 * 200 with every assertion green from a consumer connection — search, listing, chart, latest
 * updates, download page. What had changed was the network the measurement was taken from: from
 * the runner's egress every uptodown address answered 404, and the two previous nights the same
 * pipeline had been green.
 *
 * The defect was therefore not in a selector and not in `UptodownConfig`: it was in a **diagnosis**
 * that named one cause with no alternative, and named the one cause it could not have been.
 * Whoever read that issue was sent to rename a subdomain sitting exactly where it always had.
 *
 * So the choice of message is a pure function of one extra answer, and it has a test of its own
 * that needs no network — because a diagnostic that can only be exercised by the failure it
 * describes is a diagnostic nobody ever checks.
 *
 * ### The question that separates the two worlds
 *
 * Not *"is this address there?"* — the 404 already answered that — but **"does the language root
 * still answer?"** `UptodownStoreAdapter.healthCheck` is that question: a `HEAD` on
 * `en.uptodown.com`, the address every other URL in this adapter is built from. It cannot have
 * *moved* without the store having left the host altogether, which is a far larger claim than a
 * changed slug.
 *
 * Three things about the probe worth writing down:
 *
 *  - **`HEAD` and `GET` agree on this store**, measured 31/08/2026 from a consumer connection: root
 *    200/200, a real listing 200/200, a path that does not exist 404/404, a subdomain that does not
 *    exist 404/404. Without that, the probe would be answering a different question from the one
 *    that failed;
 *  - **it goes through the adapter**, not through a hand-rolled request. A canary that measured
 *    with a second client would be measuring that client — the rule this project paid the most for;
 *  - **`healthCheck` has no production caller**, so this is also the only place that ever runs it.
 *    That is worth knowing rather than fixing here: an aliveness probe nobody asks is a separate
 *    matter from a 404 nobody can read.
 *
 * ### What the three messages must not do
 *
 * Send the reader to the wrong job. A 404 on one address and a 404 on every address lead to
 * opposite work — rewriting an address, and touching nothing at all — and `StoreError.NotFound`
 * carries no HTTP code, no URL and no breadth, so it cannot tell them apart on its own. Widening
 * the contract to carry them was considered and rejected: `:store:api` would grow a field for a
 * diagnostic want rather than because an adapter does not fit, and the root's answer settles the
 * question without it.
 */
internal fun uptodownNotFoundMessage(what: String, root: StoreResult<Unit>): String = when (root) {
    is StoreResult.Success -> "$what: **404, and the language root answers.** So this is about " +
        "this one address and not about uptodown: either the URL scheme moved — a listing lives " +
        "at `{slug}.en.uptodown.com/android`, and a different subdomain 404s without any " +
        "selector failing — or the reference app is no longer on the store. Open the address " +
        "that failed in a browser before touching `UptodownConfig`, and if it is only the app " +
        "that is gone, re-anchor the constant instead of the template."

    is StoreResult.Failure -> when (val rootError = root.error) {
        StoreError.NotFound -> "$what: **this run verified nothing about uptodown's parsers.** " +
            "The address answered 404 and so did the language root, so this check was " +
            "**skipped, not failed** — see `uptodownIsEgressRefusal`. It is not a changed URL " +
            "scheme and not a markup change: `${UptodownConfig.DEFAULT_BASE_URL}` is the root " +
            "that search, the chart and the recent list are built from — the listing and the " +
            "download page come from `appUrlTemplate`, which is a **separate** field — and it " +
            "answers 200 from a consumer connection. That shape is uptodown refusing this " +
            "egress with a 404 in place of a 403, which is also why nothing reached " +
            "`StoreError.Blocked` and no rung of the escalation ladder was offered it. **Do not " +
            "rewrite selectors.** Repeat these same URLs from a consumer connection, which is " +
            "where reachability is measured in this project and not from a datacentre. If the " +
            "root does not answer from there either, the premise of this message is gone and it " +
            "is the base URL that needs changing: note that a deliberate retirement arrives as " +
            "410, which `StoreErrors` folds into this same `NotFound` and nothing here can tell " +
            "from a 404."

        else -> "$what: **404, and the language root failed too — differently: $rootError.** The " +
            "root's own answer names the case better than a 404 ever could: read that one, and " +
            "treat this line as its symptom rather than as a second fault."
    }

    StoreResult.Unsupported -> "$what: **404, and the health probe answered `Unsupported`**, " +
        "which `UptodownStoreAdapter.healthCheck` never returns. Read this as our code having " +
        "changed, not uptodown."
}

/**
 * Whether that 404 is uptodown **refusing this egress** rather than an address that has moved —
 * which is the difference between a canary that has found something and a canary that could not run
 * at all.
 *
 * It is read off the same one answer [uptodownNotFoundMessage] reads, and it is the root 404 case.
 * `en.uptodown.com` answers 200 from a consumer connection and 404s only from an egress being
 * refused — a refusal wearing a 404 in place of a 403, which is also why nothing ever became
 * `StoreError.Blocked` and no rung of the escalation ladder was offered it.
 *
 * **It is not the address every URL here is built from, and the earlier draft of this note said it
 * was.** `UptodownConfig.baseUrl` governs three of the five checks — search, the chart, the recent
 * list; `appUrlTemplate` is an independent field and governs the listing and the download page. So
 * the root is a genuinely separate question for two of the five and the *same host and path prefix*
 * for the other three. Two consequences worth stating rather than discovering: the probe is a
 * weaker signal for search than it looks, and a `DEFAULT_BASE_URL` that ever became simply wrong
 * would produce exactly this reading — which is why the message now sends the reader to re-measure
 * the root and says what to change if it does not answer from a consumer connection either.
 *
 * The probe has to be the **root** and not one of those paths, and that is measured rather than
 * assumed: on 03/09/2026 `zzqxwvnbtklmj.en.uptodown.com` — a subdomain that does not exist — 404s
 * on `/` and on `/android`, but `/android/search?query=telegram` answers **200**. A probe pointed
 * at search would therefore answer "alive" from a host that is not there.
 *
 * ### Why the canary skips this instead of failing on it
 *
 * Because a failing test is a claim about the adapter, and this is a claim about the **network the
 * measurement was taken from**. The nightly runs from a datacentre; this project measures
 * reachability from a consumer connection precisely because the two do not agree, and on 31/08/2026
 * they disagreed completely — five red checks here against five green ones minutes later, same
 * adapter, same User-Agent, same URLs. What that produced was an issue asking someone to repair a
 * store that was not broken, and it sent its reader to rename a subdomain sitting exactly where it
 * had always been. A canary that keeps doing that nightly is worse than no canary: it is what
 * teaches people to ignore red.
 *
 * **Skipped** is also the honest JUnit outcome rather than a convenient one. The premise of the
 * measurement — that this network can reach uptodown — does not hold, so there is nothing to
 * conclude in either direction, and the message opens by saying that this run verified nothing.
 *
 * It is deliberately not a green, and the difference has to be **made** visible rather than
 * asserted: a skipped test is two clicks deep in the Gradle HTML report, whose index still reads
 * "100% successful", and the XML that carries the abort message was not even among the uploaded
 * artifacts. So `canary.yml` now uploads `build/test-results/` as well and writes every skipped
 * check, with its message, into the run's step summary. Without that step this whole branch would
 * be silence dressed as success — and the price it buys has to be said plainly: **while uptodown
 * skips, nothing is checking its parsers.** That is acceptable only because this pipeline blocks
 * nothing and the alternative is a nightly issue about a store that answers 200 from where the app
 * actually runs.
 *
 * **What has never been measured, and the step summary is how it will be:** whether the language
 * root 404s from the runner's egress at all. On 31/08/2026 `healthCheck` had no caller, and the
 * five surfaces recorded in the store table do not include the root — so a WAF rule scoped to deep
 * paths, which would leave `/` at 200, fits the observation just as well as a host-level refusal.
 * If it is the former, this branch will not fire and the nightly will fail as before; the summary
 * will then say which of the two it was, in one line, on the next occurrence.
 *
 * ### What it must not swallow, and this is the whole of its width
 *
 * A root that **answers** while one address 404s stays a failure, because that is the real thing —
 * a moved URL scheme, or a reference app that has left the store — and it is ours to fix. So is a
 * root that fails in any other way. Only the one case where the whole store is unreachable from
 * here is skipped, and widening this predicate by a single reading would start hiding the failure
 * it exists to keep visible.
 */
internal fun uptodownIsEgressRefusal(root: StoreResult<Unit>): Boolean =
    root is StoreResult.Failure && root.error == StoreError.NotFound
