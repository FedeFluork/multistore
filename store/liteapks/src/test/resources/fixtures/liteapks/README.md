# liteapks fixtures

**Real** pages, captured on **25/08/2026** from an Italian consumer IP (Wind Tre, AS1267), with
**OkHttp** and the Chrome mobile User-Agent declared by `LiteapksConfig.DEFAULT_USER_AGENT`. None
has been modified: they are the bytes the server sent, gzipped.

To look at one: `gzcat detail.html.gz | less`.

**The client matters more here than on any other store, and that is not a ritual formula.** On the
same URLs, in the same minute:

| Client | Outcome |
|---|---|
| `curl` HTTP/2, Chrome mobile UA | **403** `cf-mitigated: challenge`, `cType: 'managed'` |
| `curl --http1.1`, same UA | 200 |
| **OkHttp HTTP/2, Chrome mobile UA** | **200** — this is what captured them |
| **OkHttp forced to HTTP/1.1** | **403** |
| OkHttp HTTP/2 with UA `curl/8.7.1` | **403** |

A fixture taken with the wrong client here would not be a poorer fixture: it would be a block page.
It is the same lesson as apkmirror's HTTP/1.1 pin, third occurrence.

| File | URL | Outcome |
|---|---|---|
| `search.html.gz` | `liteapks.com/?s=telegram` | 200, 61 KB — **7 results**, declared total `(7)` and true |
| `search-page1.html.gz` | `liteapks.com/?s=game` | 200, 95 KB — **18 results**, declared total `(60)` **saturated** |
| `search-page2.html.gz` | `liteapks.com/?s=game&paged=2` | 200, 96 KB — another **18**, different from the first |
| `search-last-page.html.gz` | `liteapks.com/?s=game&paged=4` | 200, 60 KB — **6**, the partial last page |
| `search-empty.html.gz` | `liteapks.com/?s=zzqxwvnbtklmj` | 200, 41 KB — **0 results and no container** |
| `detail-game.html.gz` | `liteapks.com/minecraft.html` | 200, 137 KB — a **game**: package, 6 screenshots |
| `detail.html.gz` | `liteapks.com/telegram.html` | 200, 102 KB — an **app**: no package, no screenshots |
| `detail-play-params.html.gz` | `liteapks.com/plus-messenger-2.html` | 200, 107 KB — Play link **with `&hl=en&gl=US`** |
| `not-found.html.gz` | `liteapks.com/questa-app-non-esiste-2026.html` | **404**, 38 KB of full page |
| `download-game.html.gz` | `liteapks.com/download/minecraft-11909` | 200, 95 KB — **6 files in 3 groups**, version on the row |
| `download.html.gz` | `liteapks.com/download/telegram-810` | 200, 90 KB — **3 files in 2 groups**, version on the heading |
| `download-single.html.gz` | `liteapks.com/download/project-sekai-73826` | 200, 95 KB — **1 file, no `#dl-versions`** |
| `download-original.html.gz` | `liteapks.com/download/protake-mobile-cinema-camera-96278` | 200, 89 KB — **4 files, 2 "Original file on Google Play"** |
| `download-slot.html.gz` | `liteapks.com/download/minecraft-11909/1` | 200, 96 KB — `data-link` **already percent-encoded** |
| `download-slot-raw-spaces.html.gz` | `liteapks.com/download/telegram-810/1` | 200, 91 KB — `data-link` **with raw spaces** |

## Three `curl` findings these fixtures contradict

| with `curl` | these fixtures show |
|---|---|
| "search capped at **one** page, ~9 results" | 18 per page, four pages |
| "`?s=…&paged=2` → **404**" | 200 with different results — the 404 is `paged=5` |
| "the detail selectors are probably wrong" | `#tab-desc`, `#mod-info` and `#screenshotScroll` still exist |

What remains true is the risk: Cloudflare **really does challenge** here, unlike pdalife where it
sits in passive CDN mode.

## The result count saturates at sixty

`h1#search-title` declares the total in parentheses, and it is true while it is small: `telegram`
says `(7)` and the rows are 7, `minecraft` says `(8)` and they are 8. Above that it **saturates**:

| query | declared |
|---|---|
| `a` | 60 |
| `e` | 60 |
| `game` | 60 |
| `mod` | 60 |
| `pro` | 60 |

At 18 per page that is exactly four pages, and `paged=5` answers 404. `search-last-page` is the
fourth: six rows, and `hasMore` must say `false`.

## The Google Play advert is on every listing, and works in 84% of cases

This store's most important finding. Across **31 sampled listings**:

| read | outcome |
|---|---|
| the page's first `play.google.com` | right package **26 times**, `io.apkmody.sai` **5 times** |
| inside `.app-stats .app-stat` | 26 real packages, 5 absences — no false positives |
| `io.apkmody.sai` advert present | **31 out of 31** |
| advert inside `.app-stats` | **0 out of 31** |

It is a worse trap than pdalife's, where the advert came first *always* and the defect would have
shown immediately. Here the naive read works almost always, and when it is wrong it does not return
`null` — it returns **another app's package**, which step 4 of the pre-install pipeline would use as
truth.

`detail.html.gz` is one of the five listings with no real link, and it is committed for that reason:
the `theAdvertIsNotThePackage` test checks **both** things — that the advert is there, and that
inside the container there is nothing — because otherwise it would only prove that a `null` comes
out of an empty page.

`detail-play-params.html.gz` covers the other half of the same field: five listings out of
thirty-one write `?id=org.telegram.plus&hl=en&gl=US`, and a cut after `id=` would produce
`org.telegram.plus&hl=en&gl=US` — a package matching no APK, i.e. an installation blocked at the
last metre.

## The version lives in two places, and neither is enough

Counted across **66 real file rows**, over 31 download pages:

| where the version is | rows |
|---|---|
| in the row's label (`v1.26.10.4 Final - Mod 1`) | 22 |
| only in the group heading (`v12.10.1 - Mod`) | 44 |

`download-game.html.gz` is the first case: its three headings say "Minecraft - Official Versions",
"Minecraft - Beta Versions", "Minecraft - Full/Paid" — reading them would give six files three
repeated names. `download.html.gz` is the second: its rows say only `Premium/Web` and `Premium`, and
without the heading two files would have the same empty name.

Eleven of the 22 are the "Original file" block, where the version is written **without the `v`**
(`3.0.20 Original`).

## Two markup forms for the same page

| form | pages out of 31 |
|---|---|
| with `div#dl-versions` and `button.dl-version-tab` | 17 |
| no container, a single block | 14 |

`download-single.html.gz` is the second form. Anchoring the row selector to `#dl-versions` would
lose **fourteen pages out of thirty-one** silently: those listings would say "nothing to download"
while having a file.

## `download-original.html.gz`: the unmodified APK, on another CDN

The "Original file on Google Play" block offers the original file, directly and with no intermediate
page. Of the 11 rows of this kind in the sample:

- **8 answer** (206 with `Range`), and 6 are `.xapk`;
- **1 answers 404** (`gp3.liteapks.com`);
- **2 sit on NXDOMAIN hosts** (`play.liteapks.com`, `gp.liteapks.com`).

They are the reason for `supportsSplits = true` and for `preflight`. And on one listing out of
thirty-one (`minecraft-earth`) they are **the only file that exists**: discarding them would mean a
listing that offers nothing.

## The transit permit, measured in both directions

`download*.liteapks.dev` demands two things together; neither is enough alone.

| request | outcome |
|---|---|
| bare | 403 "Access is not allowed." |
| with `?token=` | 403 |
| with `Referer: liteapks.com/…` | 403 |
| **with both** | **200**, 77,817,431 bytes |
| with a `Referer` from another domain | 403 |

The token is `btoa(btoa(expiry))`, written in the clear in their `site.js`. What the worker really
checks:

| token | outcome |
|---|---|
| expiry in 3 hours | 200 |
| expiry in 10 days | **200** |
| expiry already past | 403 |
| non-numeric text | 403 |
| single base64 | 403 |

No signature, no key: it is a client-declared expiry. The note on why computing it sits on the
permitted side of the line is in `LiteapksRefs.downloadToken`.

One detail that separates two diagnoses: a space encoded as `+` instead of `%20` produces **404
`NoSuchKey`** (R2 is behind it), not 403. The 403 is about the permit, the 404 about the key.

## No challenge page, and this time it is a documented absence

The fixture checklist asks for "a challenge page" too. There is none here, and that is not an
oversight: from this network, with the real client, liteapks never challenged us — 0 challenges
across some ninety requests spanning search, listings, download pages and slots. The challenge page
exists and is reachable with `curl` (403 `cf-mitigated: challenge`, `cType: 'managed'`), but
committing it as *this* adapter's fixture would mean freezing the response to a client we do not
ship.

Whoever sits in a worse reputation band will really get it — Obtainium issue #2669 cites real users
being blocked — and it is for that case that the silent WebView rung of the escalation ladder
exists, still without measured consumers.

## The CDN that answers 429 to everyone

`down.appsupload.com` serves part of the files — Minecraft, for instance — and on 25/08/2026 it was
answering `429 {"code":"too_many_requests","message":"Your account has made too many requests"}` to
**every** request, including the one to the root and the first request ever made from this IP.

It is not our budget: it is that of the account owning the CDN. It is the same family as an1's
`x-ratelimit-*` headers — **a published number is not necessarily a number that concerns you** — and
the practical consequence is that `preflight` reports it as "file unavailable", not as "the store is
rate-limiting us".

## What could not be measured

On modyolo the match between the Play page's package and the modified APK's was verified against the
bytes: eight files downloaded and read with `aapt2`, eight out of eight. Here it could not be, and
not out of laziness: this store's APKs weigh between 40 MB and a gigabyte, and downloading eight of
them to read their manifest would have been traffic disproportionate to the question.

The package is declared all the same, for the reason already written for pdalife: the two directions
do not cost the same. Declaring it, a MOD that changed package would be **blocked** by step 4 (a
visible fault, in the cautious direction); not declaring it, step 4 would have nothing to work with
on exactly the store that needs it most.
