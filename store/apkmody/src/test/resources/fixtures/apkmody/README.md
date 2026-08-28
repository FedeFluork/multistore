# apkmody fixtures

**Real** pages, captured on **24/08/2026** from an Italian consumer IP (Wind Tre, Milan), with the
Chrome mobile User-Agent declared in `ApkModyConfig.DEFAULT_USER_AGENT`. None has been modified:
these are the bytes the server sent, gzipped because uncompressed they weigh 1.6 MB — almost all
of it inline CSS and JavaScript.

The tests read them with `Fixtures.html(...)`, which decompresses in memory. To look at one:
`gzcat detail.html.gz | less`.

| File | URL | Result |
|---|---|---|
| `search.html.gz` | `/?s=spotify` | 200, 234 KB, **20 results** |
| `search-empty.html.gz` | `/?s=ღყშ` | 200, 217 KB, **0 results** |
| `detail.html.gz` | `/apps/spotify-pro` | 200, 331 KB |
| `download.html.gz` | `/apps/spotify-pro/download` | 200, 242 KB |
| `history.html.gz` | `/apps/spotify-pro/history` | 200, 232 KB, **4 versions** |
| `history-version.html.gz` | `/apps/spotify-pro/history/xyTAa4R6VE` | 200, 238 KB |
| `history-other-app.html.gz` | another app's history | 200, 226 KB — used **only** by the contradiction test |
| `not-found.html.gz` | a non-existent listing | **404**, 226 KB |
| `popular.html.gz` | `/popular` | **200**, 268,078 bytes, **12** entries |

## The host is `.mobi`, and the other two are worse than dead

The `.com` domain **is not a fallback**: its deep paths answer 301 towards an unrelated site — the
same pattern as the `.fun` one, which redirects to an IPTV site. Following that redirect would mean
presenting a third party's page to the user as though it were the store they chose. Only `.mobi`
answers with the catalogue, and it answers **without** a User-Agent too (verified: the root gives
200 with Chrome's UA, curl's and none).

## Why the "no results" query is in Georgian

apkmody searches **fuzzily**, not by substring, and the difference is measured:

| Query | Results |
|---|---|
| `zzqxwvnbtklmj` | **20** (`brazzers`, `mozzart`, `coinbazzar`, `teen-buzz`…) |
| `xqjvwkpzfhbd` | 20 |
| `kkkkkkkkkkkkkkkkkkkkkkkkkkkkkk` | 20 |
| `vvvvvvvvvvvvvvvv` | **1** — the game `VVVVVV` |
| `ъьэ` (Cyrillic) | 6 |
| `ღყშ` (Georgian) | **0** |

No string of Latin letters, however absurd it looks, produces zero results. An alphabet absent from
the titles is needed. This is not pedantry: without the empty branch genuinely exercised, a parser
returning those twenty results for a query with no matches would pass them to the aggregation, and
with nine stores an invented result is worse than no result.

## The footer stays, and the empty fixture contains it

On an empty search apkmody leaves the results container **empty** but keeps the footer intact with
"Trending" and "Latest": several real apps, with hrefs of the same shape as results. That is why
the empty fixture is captured whole: the "no results" page is not a page without links to apps.

**What actually excludes them, measured:** the card selector, not the container. Removing the
container from the selector leaves the suite green; the footer is made of list items and has
neither the card element nor the card title class. The container stays in the selector anyway,
because the only alternative is betting the footer keeps being built that way. On **uptodown** the
same bet lost — there the cards shown in place of results are identical to results, and only the
container tells them apart.

## There is no challenge page, and that is not an omission

The fixture checklist asks for a challenge page too. apkmody **has none**: no 403, no mitigation
header, no session cookie across ~40 requests. In its place is the **404**, the only error outcome
this store actually produces — and it is a complete 226 KB page with menu, footer and trending
apps, whose only distinguishing mark is a `404` heading.

## What was verified against the real APK, not inferred from the name

The CDN path is `cdn.topmongo.com/packages/{packageName}/{Name}_{versionName}_{versionCode}_{hash}.apk`.
Both halves were confirmed by downloading a file and reading it:

```
$ aapt2 dump badging ZX-FLY_1.0.2_6_235298.apk
package: name='com.lcfld.zxfly' versionCode='6' versionName='1.0.2'
```

The URL was `cdn.topmongo.com/packages/com.lcfld.zxfly/ZX-FLY_1.0.2_6_235298.apk`: the path segment
**is** the `packageName` and the penultimate field of the name **is** the `versionCode`. It is the
only source of version codes apkmody publishes.

## The declared size is rounded, and by how much

One app's declared size means 158,314,004 bytes in binary units; the CDN delivers **158,310,989**
(measured with a `HEAD`). Three thousand bytes of discrepancy are enough for the download engine to
declare a finished connection dropped — it happens on apkcombo, and the diagnosis was "no
connection" in front of a complete file. Hence no expected size.

## `popular.html.gz` — the chart

Captured 25/08/2026, ~22:07 UTC, with OkHttp over HTTP/2 and a Chrome mobile UA.

The chart is read from the structured data and not from the cards, because **the structured data
declares the position**. From the cards it would be inferred from arrival order, which is the same
thing until the theme inserts a promotional row in the middle — and on a store that lives on
advertising that is the case to expect, not the exception. The same choice already made on
liteapks.

A page parameter returns **the same bytes**: the chart is twelve entries and ends there.

**On this page the list block is the first of the two `ld+json` blocks** (the second is the SEO
plugin's graph), so choosing by type makes no difference here. The case that makes it necessary — a
breadcrumb block, which also has list elements, emitted first — lives in a **constructed** document
inside `ApkModyPopularParserTest`, and the test says why.

The SEO suffix is attached to all twelve entries and appears in no listing's title: keeping it would
give two different apps for the same app, on the same store.
