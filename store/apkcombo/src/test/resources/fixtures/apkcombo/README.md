# apkcombo fixtures

**Real** pages, captured on **24/08/2026** from an Italian consumer IP (Wind Tre, Milan), with the
Chrome mobile User-Agent declared in `ApkComboConfig.DEFAULT_USER_AGENT`. None has been modified:
these are the bytes the server sent, gzipped because uncompressed they weigh 530 KB and add
nothing to the repository.

The tests read them with `Fixtures.html(...)`, which decompresses in memory. To look at one:
`gzcat search.html.gz | less`.

| File | URL | Result |
|---|---|---|
| `search.html.gz` | `/search/?q=telegram` → 302 → `/search/telegram` | 200, 113 KB, 20 results |
| `search-empty.html.gz` | `/search/?q=zzqxwvkjhgfdsapoiuytrewq` | 200, 102 KB, **0 results** |
| `detail.html.gz` | `/telegram/org.telegram.messenger/` | 200, 85 KB |
| `download.html.gz` | `/telegram/org.telegram.messenger/download/apk` | 200, 100 KB, 4 variants |
| `download-old.html.gz` | `/telegram/org.telegram.messenger/download/phone-12.9.2-apk` | 200, 100 KB, same variant structure |
| `old-versions.html.gz` | `/telegram/org.telegram.messenger/old-versions/` | 200, 55 KB |
| `not-found.html.gz` | `/doesnotexist/qzxvnpwmklj.nonexistent.package/` | **404**, 55 KB |
| `recent-feed.xml.gz` | `/latest-updates/feed` | **200**, `text/xml`, 82,662 bytes, **96** entries |

## Why the "no results" fixture uses that query and not another

apkcombo searches **by substring**, and this was measured: an obviously nonsensical query still
returns 9 apps because it contains a real word, and another returns 23. A query that looks absurd
is not absurd enough. What is needed is a token containing no real substring — hence
`zzqxwvkjhgfdsapoiuytrewq`.

This is not fixture pedantry: a parser returning those 23 apps as "results" would pass them to the
aggregation, and with nine stores an invented result is worse than no result.

## There is no challenge page, and that is not an omission

The fixture checklist asks for a challenge page too. apkcombo **has none**: verified across ~30
requests — no 403, no mitigation header, no session cookie, and the same response byte for byte
with Chrome's UA, curl's and none. In its place is the **404**, the only error outcome this store
actually produces. Inventing a challenge the store does not send would give a test that passes on
an imaginary case.

## Search pagination does not exist, and that is measured

Later page parameters on the search URL return **the same twenty results** as the first page — the
comparison was made over the complete list of links, not by eye. The adapter therefore declares no
further pages and returns later ones empty without making the request.

## `recent-feed.xml.gz` — the new-releases feed

Captured 25/08/2026, ~22:07 UTC, with OkHttp over HTTP/2 and the same Chrome mobile UA.

Two things this fixture pins down that no other covers:

- **it is XML.** Read with Jsoup's HTML parser, `channel > item > link` returns the **empty
  string** — `<link>` in HTML is an empty element and the URL becomes a sibling text node. The
  test that exercises this is `ApkComboFeedParserTest`;
- **each entry's URL carries the `packageName`** (`/{slug}/{packageName}/`). It is the only one of
  the four new-release sources that does, and the test verifies it on all 96 rows.

**96 and not the 98 of the first probe**, and that is not an error: a feed is a window, and it
moves between one request and the next. It is also why the tests look up an entry by title rather
than taking the first.

The pages that might have replaced it are of no use: the top-apps, trending and new-apps pages
answer 200 with **zero links to a listing** — they have the right headings and JavaScript writes
the content. They differ from one another only in their canonical link and in a randomly chosen
tag cloud.

## `download-no-variants.html.gz` — the dead end, and what gets it out

`https://apkcombo.com/ime-telegram-ai-messenger/com.iMe.android/download/apk`, captured
29/08/2026, **HTTP 200**, 92.418 bytes, with `curl 8.7.1` and the Chrome mobile UA. apkcombo is the
one store where curl is a legitimate capture client and that is measured, not assumed: `/download/apk`
comes back byte for byte identical with a Chrome UA, with curl's own and with none at all.

It is **another app** than the other fixtures, and it has to be: Telegram has variants on its
latest page, so the dead end is not photographable from it. The URL redirects — the slug in the ref
is not canonical (`ime-messenger` → `ime-telegram-ai-messenger`), which the adapter already follows.

What this page is for, and it is the whole point:

- **zero `/r2?` links.** The latest-version segment (`apk`) publishes no downloadable variant for
  this app, so the listing used to arrive with an empty version list and the screen said "this store
  publishes no installable package for this app" — a dead end with nothing saying why;
- **the version list is on it anyway**: `ul.list-versions a.ver-item`, 3 rows (12.9.4, 12.9.3,
  12.9.2), the same markup `/old-versions/` uses at greater length. That is why the fallback costs
  **no extra request**: the page that proved there is nothing to install is the page that says where
  the files are.

Measured on the same day, for the record: this app's `/old-versions/` page answers 200 with **31**
`ver-item` rows. Reaching for it would have worked too, and would have cost a second request to get
28 rows nobody has asked for yet — the version-history section fetches them when opened.
