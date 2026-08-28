# pdalife fixtures

**Real** pages, captured on **25/08/2026** from an Italian consumer IP (Wind Tre, AS1267), with the
Chrome mobile User-Agent declared by `PdalifeConfig.DEFAULT_USER_AGENT`. None has been modified:
they are the bytes the server sent, gzipped.

To look at one: `gzcat detail.html.gz | less`.

| File | URL | Outcome |
|---|---|---|
| `search.html.gz` | `pdalife.com/search/minecraft/` | 200, 69 KB — **20 rows, 18 Android**, 2 iOS |
| `search-page2.html.gz` | `pdalife.com/search/minecraft/page-2/` | 200, 59 KB — **14 rows, 12 Android**, 1 iOS, **1 PSP** |
| `search-empty.html.gz` | `pdalife.com/search/zzqxwvnbtklmj/` | 200, 35 KB — **0 results and one apology row** |
| `search-other-os.html.gz` | `pdalife.com/search/procreate/` | 200, 67 KB — **20 results, none Android** |
| `search-unrated.html.gz` | `pdalife.com/search/turbogram/` | 200, 36 KB — **1 Android result, rating 0** |
| `detail.html.gz` | `pdalife.com/telegram-android-a14523.html` | 200, 96 KB — a **program**, with the Play link |
| `detail-mod.html.gz` | `pdalife.com/real-gangster-crime-android-a32255.html` | 200, 85 KB — a **modified game** (`Money Mod`) |
| `detail-no-package.html.gz` | `pdalife.com/unleashed-pixel-dungeon-android-a27009.html` | 200, 80 KB — **without** the offers container |
| `download.html.gz` | `mobdisc.com/dwfe8bc99d/download.html` (via `/dwn/fe8bc99d.html`) | 200, 10 KB — reCAPTCHA v3 |
| `download-mod.html.gz` | `mobdisc.com/dw6d2d7bca/download.html` | 200, 10 KB — **an advertising `.apk`** |
| `not-found.html.gz` | `pdalife.com/questa-app-non-esiste-android-a99999999.html` | **404**, 33 KB of full page |

## The search is not the obvious endpoint, and the difference is measurable

`GET /suggest/?query=` looks ideal: JSON, robots-allowed, with `id`, `hash`, `alias`. The endpoint
exists and is exactly as it looks. **It is not a search:**

| | `/suggest/?query=` | `/search/{slug}/` |
|---|---|---|
| results | **always 10** | 20 per page |
| pagination | none (`page`, `limit`, `count` ignored, identical bytes) | `page-2`, `page-3`… |
| query with no results | **10 random apps** | **0 results** |
| version, rating, description, category | absent | present |
| weight | 5 KB | 69 KB |

The deciding row is the third. `?query=zzqxwvnbtklmj` answers "SEGA NET MAHJONG MJ", "Zoe", "Zoi",
"ZEG", "Zane"… In an aggregator merging nine stores, a source that answers ten irrelevant results to
every query is not a source: it is noise the user must learn to ignore, on every search, forever.

**`/search/?search={q}` looks like a shortcut and is not.** It answers 200, it has results, and it
ignores them completely: it is the "Search the site" page with a generic list (ids 63, 64, 65…),
identical for any query and any `page`. It is the form easiest to test on a single app and believe.

## Five Google Play links, four of which are the same advert

This store's costliest finding, and the reason `detail-no-package.html.gz` is committed.

`detail.html.gz` contains **five** occurrences of `play.google.com/store/apps/details?id=`. Four
are `cc.peacedeath.peacedeathapp` — an advert — and the real one (`org.telegram.messenger`) is the
**last**. Across 17 sampled listings:

| read | outcome |
|---|---|
| the page's first `play.google.com` | `cc.peacedeath.peacedeathapp` **17 times out of 17** |
| inside `.game-download__stores` | 12 real packages, 5 absences |

The 5 absences are apps that are not on Google Play, and `detail-no-package.html.gz` is one of
them: the container is missing entirely, while the advert is in its place. The
`missingOffersMeansNoPackage` test checks **both** things — that the container is absent and that
the advert is present — because otherwise it would only prove that a `null` comes out of an empty
page.

### What could not be measured

For modyolo, the match between the Play page's package and the modified APK's was **verified
against the bytes**: eight files downloaded and read with `aapt2`, seven marked MOD, eight out of
eight. Here it could not be, and not out of laziness: the file sits behind reCAPTCHA v3, and the
only way to get it is for a person to press the button.

It is declared all the same, because the two directions do not cost the same — the full note is at
the head of `PdalifeDetailParser`. In short: declaring it, a MOD that changed package would be
**blocked** by step 4 of the pipeline (a visible fault, in the cautious direction); not declaring
it, nothing would stop the download page's advert being installed.

## `download-mod.html.gz`: three buttons, and neither `.apk` is the app

```html
<a class="b-download__button b-download__button_state_inactive js-dwn-btn"
   href="#/download/real-gangster-v6-3-5-mod.apk" data-dwn="6d2d7bca">     <- the real one, inert
<a class="b-download__button" href="https://mq.omenpenial.com/…">            <- advert
<a class="b-download__button" href="https://api.monstervpn.cc/media/apk_versions/monsterVPN-2.4.3.apk">
```

The third is a real APK of another app. The first has an `href` that is a **fragment**: resolved
against the page it becomes `https://mobdisc.com/dw6d2d7bca/download.html#/download/…apk`, i.e. an
address ending in `.apk` that would download the HTML.

The `theLandingPageOffersAnAdvertApk` test proves it positively: **two** absolute URLs end in
`.apk`, the first is the page itself and the second is the advertising. There is nothing to read
there without really running the reCAPTCHA, and that is why the user opens that page.

The real address comes from `POST /get_key/` with the token from
`grecaptcha.execute('6Lceo_8UAAAAAGKPGkR-373630tIcnJuXBybKBGp', {action:'get_key'})`, as read in
`mobdisc.com/js/wp.js`. Calling it without having run the challenge would be **pretending** to have
solved it, which is the line this project does not cross.

## The two fixtures added after fault injection, and why

Defences are checked by removing them: a test that passes both with and without the defence it is
meant to prove is not a test, it is a caption. Applied to this adapter, injection failed **five**
tests out of twelve on the first pass. Two were repaired by adding one fixture each; three were
defences that simply do not exist.

### `search-other-os.html.gz` — twenty results, none Android

The operating-system filter lives in the selector (`:has(p.catalog-item__title a.color-android)`).
Removing it, against the "minecraft" fixtures, **changed nothing**: the iOS rows came in, the ref
rejected them because it requires `-android-`, and the count stayed 18.

The case that separates the two defences is the one where the Android rows are **zero**.
`/search/procreate/` — iPad brushes — has twenty, all iOS. Without the filter in the selector,
`mapRowsOrFail` would find twenty rows, be unable to read any, and would say `ParseFailure`:
**"this store broke" instead of "there is nothing for Android"**.

That the ref rule is *also* needed is proven separately, in `RefsTest`, by calling `refFromUrl`
directly: a ref also comes from Room, without going through any search.

### `search-unrated.html.gz` — one result, rating zero

`/search/turbogram/` returns a single row: `TurbogramPro Advanced Telegram`, with
`rating-circle_rating_0`. In the other search fixtures the minimum is **1**, so the rule "zero means
no rating" was exercised by nothing.

Zero is not a judgement: the listing declares `worstRating = 1`, and the rating block appears on
every row even when nobody has voted. Reporting it as `0.0` would tell the user that app has been
rated terrible.

## Three selector clauses that are not defences, and the code now says so

The other three injections stay green, and the right answer was not to add a test but to correct
the comment describing them:

| clause removed | why it stays green |
|---|---|
| `ul.catalog-list >` | the sidebar uses `li.side-top__item`, not `li.catalog-item`: the container excludes nothing that is not already excluded |
| `.js-list-item` | the "Oops, maybe try another request?" row has no title, so the OS filter already discards it |
| `[data-version_id]` | the advertising banner sits **after** the close of `ul.game-versions__downloads-list`: an `li` inside that `ul` never reaches it. The attribute stays because it is **read**, not because it filters |

They stay in the selector — they are correct scoping and cost nothing — but the comment next to them
now says which of the three bears the weight, and that the other two are a net below it.

## `search-empty.html.gz`: "no results" is a row, not the absence of rows

```html
<ul class="catalog-list"> <li class="catalog-item"> Oops, maybe try another request? </li> </ul>
```

It looks like the dangerous case and is not: that row contains no title, so the OS filter already
discards it. The second class, `js-list-item`, would tell it apart anyway — and it is on the full
pages too, 21 `li.catalog-item` for 20 results — but it is a net, not a defence: see the table
above.

The same page publishes **ten** sidebar links with the same `a.color-android` as the results. They
live in `li.side-top__item`, so they were never a risk: the `ul.catalog-list` container would
exclude them, but there is no need.

## The catalogue is not Android only, and the filter lives in the selector

`search.html.gz` has 2 iOS rows out of 20; `search-page2.html.gz` has 1 iOS and **1 PSP**
(`/-psp-a34978.html`, with an empty alias); `search-other-os.html.gz` has 20 out of 20. The row is
recognised by `a.color-android` inside `p.catalog-item__title`.

The filter is in the selector and not in the parser's body on purpose, and the `procreate` fixture
is what demonstrates it: discarding the rows **after** taking them would make a search for iOS-only
apps indistinguishable from a dead selector — which is exactly what `mapRowsOrFail` exists to tell
apart.

## No challenge page, and that is not an oversight

Cloudflare is there (`server: cloudflare`, `cf-ray`) but in **passive CDN mode**: no challenge on
any read, with any client. Four User-Agents tried against the same search — `okhttp/4.12.0`,
`curl/8.7.1`, **no** UA and Chrome mobile: **all 200**.

The **size** does change, though: 50,232 bytes for the three non-browser clients, 56,849 for Chrome
mobile. The site serves less advertising to whoever does not look like a browser, and the temptation
to keep the light page is real. The fixtures are captured with the browser UA, and the adapter sends
it: the light page is a page **no human ever sees**, so a selector proven on it might not exist on
the real one.

The reCAPTCHA is the site's only protection, sits on the download's **second** hop, and is on
another domain (`mobdisc.com`, nginx, no Cloudflare): `download.html.gz` and `download-mod.html.gz`
are its fixture.

## An endpoint that exists and goes unused

`POST /app/moreVersions/` with `offset` and `id` returns `{"tpl": "<fragment>"}` with the same
accordion markup. On Telegram it behaves: `offset=4` gives one extra version, `offset=9` gives
nothing. On Slime Rancher (`id=51685`) it answers `offset=4`, `9`, `14`, `19`, `24` and `29` with
**the same two versions** the page already shows — a "while it returns something" loop would never
end.

The versions are the ones the listing publishes, and they are 1 to 4 in the fixtures. An endpoint
that paginates on one app and loops on another is not pagination.

## `data-version_id` is not a `versionCode`

It is a monotonic discriminator, and it works as one: it grows over time, so it orders the versions
without comparing version strings. But it grows **across the whole site together** — 96571 for a
2023 Telegram version, 120868 for a 2026 Slime Rancher — so it is no app's `versionCode`.

It is the same trap as `data-version-id` on uptodown. Here it is for ordering, and nothing else:
`AppVersion.versionCode` stays `null` and `VersionSelection` will answer
`UpToDate(comparable = false)`.

## Two details that look like typos and are not

- **`8.02.2026`**, with a single-digit day, next to `25.05.2026` on the same page. The format is
  `d.M.yyyy`, not `dd.MM.yyyy`.
- **`33n84e18`** and **`9n420705`** are valid download octets. They look hexadecimal and are not: a
  `[0-9a-f]{8}` pattern would discard exactly those, and the download would come out "not found" on
  a file that exists.

And one that was a typo of ours, avoided by a whisker: `itemprop='description'` appears **twice**,
the first on a `<meta>` in the head carrying the truncated description in an attribute and no text.
The generic selector took that one, and the listing came out with no description, silently.
`readsApp` found it, not a re-read.

## `recent-feed.xml.gz` — the RSS feed

| Field | Value |
|---|---|
| URL | `https://pdalife.com/rss/` |
| Date | 25/08/2026, ~22:07 UTC |
| Outcome | **200**, `application/rss+xml; charset=utf-8`, 129,947 bytes |
| Client | OkHttp 5.5.0, HTTP/2, Chrome mobile UA |
| Content | **100** `<item>`, **all Android** |

It is the only surface on this store that does not mix iOS and PSP: the search forces the wrong rows
to be discarded one by one, here there are none. The defence remains the ref all the same — a stem
without `-android-aNNN` cannot be built — because it is the only thing that keeps holding if the
feed's composition changes tomorrow.

**Five entries in a hundred are dated in the future**, the furthest at **27 May 2029**: they are
announcements of unreleased games. It is the finding that led to `TextValues.rfc1123NotFuture`, and
it is also the real case that function handles — on apkcombo, apkmirror and modyolo the future dates
are zero.

The title carries the site's verb: `The Walking Dead: A New Frontier скачать на Android`. Everything
following `скачать` belongs to the page — version, channel (`Full`, `Unlocked`, `Pro`, `Premium`:
eight forms measured) and platform — and the cut is made **on the Russian word**, not on "на
Android", because before the word there is only ever the name.
