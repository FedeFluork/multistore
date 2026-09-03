package com.multistore.store.modyolo

import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult

/**
 * Which job a 404 from modyolo actually is — decided by what the **site root** answered, and not by
 * the 404 alone.
 *
 * ### Why this exists, and why it is the mirror of uptodown's
 *
 * The message it replaces was this:
 *
 * > `NotFound`. On modyolo that means `data: null` with HTTP 200, that is the post `minecraft-19`
 * > is gone. Not an adapter fault: pick another reference app for the canary.
 *
 * Every clause of it is a claim, and the shape of the mistake is the exact opposite of the one
 * uptodown paid for on 31/08/2026. There, the message **blamed us** for something that was not our
 * fault; here it **exonerates us** for something that can very well be. "Not an adapter fault" and
 * "pick another reference app" together close the question before it has been asked, and the reader
 * who follows them changes a constant and goes back to bed.
 *
 * ### What actually answers 404 on this adapter, measured
 *
 * `data: null` with HTTP 200 is real and it is where [ModyoloDetailParser]
 * [com.multistore.store.modyolo.parser.ModyoloDetailParser] produces this error — but it is **not**
 * the only producer, and it is not the interesting one. Probed against the live API:
 *
 * | request | answer |
 * |---|---|
 * | a wrong stem on `/download/…` | **500** |
 * | an out-of-range download variant | **200** |
 * | a bogus post id on `v1/posts/{id}` | **200** with `data: null` |
 * | an **unregistered REST route** (`wp/v2/postz`) | **404**, `rest_no_route` |
 *
 * The last row is the one that matters, because **this adapter is nothing but `wp-json`**:
 * `SEARCH_PATH` is `/wp-json/wp/v2/posts` and `DETAIL_PATH` is `/wp-json/v1/posts`. If modyolo
 * hardened or renamed anything under `/wp-json/`, all four checks in the canary would go red
 * together, each one announcing that post 19 had gone — while the repair is a path in
 * [ModyoloConfig], which is the one job the old sentence ruled out.
 *
 * And that is provable without any hypothesis about the store: **three of the four call sites are
 * searches**, and a search never mentions post 19 at all. So the sentence was already wrong for
 * `search`, `unfiltered search` and `filtered search` on the day it was written — it just had not
 * been read yet.
 *
 * ### The question that separates the readings
 *
 * Not "is this address there?" — the 404 answered that — but **"does the site root still answer?"**
 * [ModyoloStoreAdapter.healthCheck] is that question, and on this store it is a genuinely
 * independent one: it fetches [ModyoloConfig.baseUrl], the bare `https://modyolo.com`, while every
 * surface the canary exercises lives under `/wp-json/`. So the root answering while a `wp-json`
 * address 404s points at the REST layer and at nothing else — which is more than uptodown's probe
 * can say, where three of the five checks share the root's own host and path prefix.
 *
 * Measured through this adapter on 03/09/2026: the root answers `Success`.
 *
 * **The limit of the probe, so nobody reads it as more than it is.** `healthCheck` is
 * `fetcher.resolveRedirect(config.baseUrl).map { }`, and `.map { }` throws away the URL it
 * resolved to — the same shortcut [com.multistore.store.apkmody.ApkModyStoreAdapter] takes, where
 * it matters much more because that store's demonstrated failure mode *is* a moved domain. Here it
 * means a root that 301s to somewhere else still reads as "the root answers", and the reader is
 * then sent to look at the address, which is the right place to be looking anyway. It is not fixed
 * here: `healthCheck` has no production caller, and widening it for a diagnostic want is what this
 * project declines to do.
 */
internal fun modyoloNotFoundMessage(what: String, root: StoreResult<Unit>): String = when (root) {
    is StoreResult.Success -> "$what: **404, and the site root answers.** So modyolo is there and " +
        "something about this one address is not. The cause depends on which check this is, and " +
        "the old message here named only the least likely one. (1) **If `$what` is a search**, " +
        "post-level causes are irrelevant: this adapter speaks only `wp-json`, so a 404 means an " +
        "**unregistered REST route** — modyolo answers `rest_no_route` with a 404 for a path that " +
        "does not exist — and the repair is `ModyoloConfig.SEARCH_PATH` " +
        "(`${ModyoloConfig.SEARCH_PATH}`) or `DETAIL_PATH` (`${ModyoloConfig.DETAIL_PATH}`), or a " +
        "`parsers.json` that overrides them. (2) **If it is `detail` or `download`**, the post " +
        "itself may be gone — that arrives as `data: null` with HTTP 200, which the detail parser " +
        "turns into this error — and then re-anchoring the canary's reference app is the right " +
        "fix. Do **not** start from (2): with a hardened `/wp-json/` all four checks fail at once " +
        "and every one of them would look like (2)."

    is StoreResult.Failure -> when (val rootError = root.error) {
        StoreError.NotFound -> "$what: **this run verified nothing about modyolo.** The address " +
            "answered 404 and so did the site root, `${ModyoloConfig.DEFAULT_BASE_URL}`, so this " +
            "check was **skipped, not failed** — see `modyoloIsUnreachable`. There is no adapter " +
            "repair that follows from it: either modyolo is not reachable from this egress, or " +
            "the site is no longer at that address at all. This pipeline runs from a datacentre " +
            "and this project measures reachability from a consumer connection, so **repeat these " +
            "URLs from one before changing anything**. If the root does not answer from there " +
            "either, the news is that the store has moved or gone, and what changes is " +
            "`ModyoloConfig.DEFAULT_BASE_URL` — plus the store table — and not a single selector. " +
            "The run's step summary lists every skipped check, and while this one skips **nothing " +
            "is checking modyolo's parsers**."

        else -> "$what: **404, and the root failed too — differently: $rootError.** The root's own " +
            "answer names the case better than a 404 can: read that one, and treat this line as " +
            "its symptom rather than as a second fault."
    }

    StoreResult.Unsupported -> "$what: **404, and the health probe answered `Unsupported`**, " +
        "which `ModyoloStoreAdapter.healthCheck` never returns. Read this as our code having " +
        "changed, not modyolo."
}

/**
 * Whether that 404 means modyolo is **unreachable from here** rather than one address having moved
 * — the difference between a canary that has found something and one that could not run.
 *
 * It reads the single answer [modyoloNotFoundMessage] reads, and it is the root-404 case only.
 *
 * ### What it must not swallow, and this is the whole of its width
 *
 * A root that **answers** while a `wp-json` address 404s stays a failure, because that is the real
 * thing and it is ours: a renamed REST path, or a reference post that has gone. So does a root
 * failing in any other way — a block, a 429, a dropped connection — each of which has its own
 * branch with its own job. Only the one reading where the whole site is unreachable is skipped, and
 * widening this predicate by a single case would begin hiding the failure it exists to keep
 * visible.
 *
 * Skipping rather than failing is the honest outcome and not a convenient one: the premise of the
 * measurement — that this network can reach modyolo — does not hold, so there is nothing to
 * conclude in either direction. It is also deliberately **not** a green, which is why
 * `canary.yml` writes every skipped check into the run's step summary; without that step this
 * branch would be silence dressed as success.
 */
internal fun modyoloIsUnreachable(root: StoreResult<Unit>): Boolean =
    root is StoreResult.Failure && root.error == StoreError.NotFound
