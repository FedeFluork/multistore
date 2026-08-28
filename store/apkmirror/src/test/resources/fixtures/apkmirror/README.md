# apkmirror fixtures

**Real** pages, captured on **24/08/2026** from an Italian consumer IP (Wind Tre, Milan). Gzipped:
uncompressed they are 2.6 MB, because every apkmirror page carries six sidebar widgets with
hundreds of rows.

**All except `challenge.html.gz` were captured with `curl --http1.1`.** That detail matters, and
not in the way it seemed: see the protocol section below.

| File | URL | Result |
|---|---|---|
| `search.html.gz` | `/?post_type=app_release&searchtype=app&s=telegram` | 200, 10 results |
| `search-empty.html.gz` | `…&s=zzqxwvkjhgfdsapoiuytrewq` | 200, **0 results** but 38 sidebar rows with the results' markup |
| `app.html.gz` | `/apk/mozilla/firefox/` | 200, packageName + screenshots + release list |
| `release.html.gz` | `/apk/mozilla/firefox/firefox-…-154-0-release/` | 200, table of 9 variants |
| `variant-apk.html.gz` | `…-154-0-6-android-apk-download/` | 200, single APK: file hash **and** certificate hash |
| `variant-bundle.html.gz` | `…-154-0-5-android-apk-download/` | 200, bundle: **only** the certificate hash |
| `interstitial.html.gz` | `…/download/?key=…` | 200, carries the final download link |
| `challenge.html.gz` | a listing **over HTTP/2** | **403**, `cf-mitigated: challenge` |
| `not-found.html.gz` | a non-existent listing | **404** |
| `recent-feed.xml.gz` | `/feed/` | **200**, `text/xml`, 24,086 bytes, **10** entries |

## The protocol finding, and the mistake made on top of it

Measured with **curl** on 24/08/2026, same IP, same User-Agent, seconds apart:

| URL | curl HTTP/2 | curl HTTP/1.1 |
|---|---|---|
| a Telegram listing | **403** `cf-mitigated: challenge` | **200** (439 KB) |
| a Telegram X listing | **403** | **200** (393 KB) |
| a Firefox release page | **403** (2 attempts of 2) | **200** (399 KB) |
| a Play Store release page | **403** (2 attempts of 2) | **200** (431 KB) |

From that table came the conclusion "apkmirror must be queried over HTTP/1.1", and with it a
configuration field, a network-tier value and a branch in the download engine.

**The conclusion was wrong.** The nightly canary disproved it, because it uses OkHttp — the client
the app actually ships — and not curl:

| Client | HTTP/2 | HTTP/1.1 |
|---|---|---|
| curl | 403 challenge | **200** |
| **OkHttp** | **200** | 403 challenge |

Retried with OkHttp adding the full set of navigation headers and the parent page's Referer: **no
difference**. What decides is the coherence between the TLS handshake and the application layer,
not the HTTP version in the abstract — curl's HTTP/2 does not resemble a browser, OkHttp's
resembles one enough; curl's HTTP/1.1 passes because there is no HTTP/2 fingerprint to compare,
OkHttp's does not.

The lesson reaches beyond this store: **a measurement taken with one client is not a measurement
of your client.** The static pin was removed; rung 1 of the escalation ladder already retries over
HTTP/1.1 by itself if it is ever genuinely needed.

`challenge.html.gz` stays as it is — it is an authentic challenge page, and it exists to prove the
detector recognises it instead of handing it to the parser.

Two things remain true and must be kept:

- **The User-Agent is mandatory.** `okhttp/4.12.0` gets **403 with 153 bytes**, re-verified.
- **The `Crawl-delay: 3` is not decorative.** apkmirror answered **429** to too dense a run of
  probes while this adapter was being written.

## Why the "no results" fixture matters more than the others

It has **38 rows carrying the results' class** and zero results. Those 38 rows are the sidebar
widgets — "Popular uploads", "Latest uploads" — which apkmirror puts on every page with the
identical markup of results. A parser looking for that class across the whole document would
return 38 arbitrary apps for a query that found nothing.

The right container is the **first** list widget of the content area, the one headed
`Results for`: on the fixture with results it holds 10, on this one **0**.

## `recent-feed.xml.gz` — the latest-releases feed

Captured 25/08/2026, ~22:07 UTC, with OkHttp over HTTP/2 and a Chrome mobile UA.

Ten entries are few, and that is why this source is still worth having: the equivalent page carries
the same information inside **424 KB** of markup, on a site declaring `Crawl-delay: 3` and
answering 429 to whoever ignores it.

The title is `{Name} {version}[ beta][ (vNNN)] by {Developer}`: three pieces of information on one
line, and it is **the only new-release source of the four that publishes the developer** — which
the inferred app key uses together with the title on a store that does not give the package.

Each entry's link is a **release** (a three-segment path); the ref is the listing, i.e. the first
two segments. None of the ten entries contains two occurrences of " by ", which is why the test for
cutting at the last occurrence uses a **constructed** feed — and says so.
