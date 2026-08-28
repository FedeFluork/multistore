# uptodown fixtures

**Real** pages, captured on **24/08/2026** from an Italian consumer IP (Wind Tre, Milan), with the
Chrome mobile User-Agent declared by `UptodownConfig.DEFAULT_USER_AGENT`. None has been modified:
they are the bytes the server sent, gzipped.

To look at one: `gzcat detail.html.gz | less`.

| File | URL | Outcome |
|---|---|---|
| `search.html.gz` | `en.uptodown.com/android/search?query=telegram` | 200, 88 KB, **36 results** |
| `search-empty.html.gz` | `en.uptodown.com/android/search?query=qzxvnpwmkljhgfd` | 200, 57 KB, **0 results and 12 suggested cards** |
| `detail.html.gz` | `telegram.en.uptodown.com/android` | 200, 173 KB |
| `versions.html.gz` | `telegram.en.uptodown.com/android/versions` | 200, 98 KB, **20 versions** |
| `download.html.gz` | `telegram.en.uptodown.com/android/download` | 200, 156 KB |
| `download-old.html.gz` | `telegram.en.uptodown.com/android/download/1191373665` | 200, 161 KB (12.9.1) |
| `not-found.html.gz` | `qzxvnpwmklj-non-esiste.en.uptodown.com/android` | **404**, 35 KB |

## Three findings that contradict the obvious guesses

1. **The search exists, and `search` works.** `/android/search` returning `410 Gone` concerns the
   **path** form (`/android/search/telegram`); the query form answers 200 under both names.
   `search?query=` is used because that is what the page's own form declares in its `action`.
2. **There is a language subdomain.** `www.uptodown.com` serves **Spanish** (`<html lang="es">`,
   "Descargar telegram"); `en.uptodown.com` serves English and the listings become
   `{slug}.en.uptodown.com`. Using `www` would fill the database with Spanish descriptions for
   every user, in all five of the app's languages.
3. **Pagination does not exist.** `?page=2` returns **the same 36 apps** as the first page, in a
   different order — compared on the set of hrefs, not by eye. The order changes on every request:
   it is randomised server-side among equally scored results.

## The fixture worth more than all the others

On a query with no results uptodown emits **no** `#content-list` at all. In its place it puts:

```html
<section class="notice">
  <p>Oops, we couldn't find any matching programs for "qzxvnpwmkljhgfd"</p>
</section>
```

and then, under "Apps you're gonna love", **twelve cards with markup identical to the results'** —
`div.item`, `data-code`, `figure img`, `.name a > h2`, `.description`. Telegram is among them.

A parser anchored on `.item` therefore answers "Telegram" to a search that found nothing — always
the same twelve, for any query. With nine stores queried together, the aggregation would have no way
of knowing they are irrelevant. **The container is the only thing separating the two cases**, and
that is verified: removing `#content-list` from the selector turns three tests red.

There is a markup difference between the two kinds of card — the suggested ones have no `<h2>`
inside the anchor — and it would have been convenient to lean on that. The parser **does not**, and
reads the link's text instead: trusting the absence of a tag in a section that is none of our
business means having a correctness that depends on how uptodown chooses to write its suggestions.
With `.name h2` the suite stayed green even **without** the container: that is, the test no longer
proved anything.

## There is no challenge page among the fixtures, and the reason is instructive

uptodown's Turnstile **does not protect the pages**: it protects the file. All seven fixtures are
200 (or 404) fetched with OkHttp and a Chrome UA, with no obstacle at all. What sits behind the
challenge is a `POST /ajax/app/{appID}/file/{fileID}/download-url`, and there is no challenge page
to capture because the widget is mounted `appearance: "interaction-only"`: it stays invisible until
Cloudflare really asks for a gesture.

That POST **we do not make**. Calling it with a token we did not obtain by running the challenge
would be pretending to have solved it, and that is the line this project does not cross.

## Two labels that look like what they are not

- "**Certificate signature**" is `26babc62540ef0c20bfc6bacf3d3b1f5`: 32 hex characters, i.e.
  **MD5**. uptodown puts an icon called `icon-40-sha256` next to it. It does not end up in
  `signerSha256` — and could not: `Sha256.parseOrNull` measures the length and returns `null`. The
  type has already done the work the label and the icon invited us to get wrong.
- "**Rating**", in the info tables, is `+12`: that is the **age classification**. The score is in
  `#rating-inner-text` (`4.3`) and goes through no table.

## The row structure has three cells, and the first is an icon

```html
<tr><td><img …></td><th>Package Name</th><td>org.telegram.messenger.web</td></tr>
```

A bare `td` selector takes the **first** cell, which contains only the decorative icon: the row
comes out "valueless" and is discarded. The effect is that the listing comes out with no
`packageName` and no SHA-256 — that is, without the two fields that make this store verifiable —
and **no selector fails**. It happened on the parser's first draft, and the tests caught it.

## The package is not the one the title suggests

uptodown redistributes **`org.telegram.messenger.web`**, not `org.telegram.messenger`. To Android
they are two distinct apps, and it is exactly the difference step 4 of the pre-install pipeline
compares.

## `top.html.gz` and `latest-updates.html.gz` — chart and recent updates

| File | URL | Outcome | Content |
|---|---|---|---|
| `top.html.gz` | `https://en.uptodown.com/android/top` | 200, 138,707 bytes | **10** entries in `#list-top-items` |
| `latest-updates.html.gz` | `https://en.uptodown.com/android/latest-updates` | 200, 99,280 bytes | **48** entries in `#content-list` |

Captured on 25/08/2026 with OkHttp 5.5.0 and the UA from `UptodownConfig`.

Neither address is advertised anywhere obvious: they are read **from the chart page**, among the
`Latest Updates` / `New Releases` / `Top downloads` tabs. The address one might guess —
`/android/new` — answers 404.

**`top.html.gz` carries two lists**, and that is why the selector is anchored to `#list-top-items`
and not to `.item`: below the chart sits the "More of our Top apps for Android" strip, 48 cards in
`#content-list`, i.e. the same container as search. Merging the two would give 58 apps claiming they
are the ten most downloaded — and that strip contains "Summertime Saga" and "College Brawl", adult
content the store does not label.

The rank sits **inside** the title (`<h2>1. Uptodown App Store</h2>`), and first place is uptodown's
own app: it is not discarded, but it should be known that that rank is not a measure of popularity.

`latest-updates.html.gz` uses the same row markup as search, so the parser is that one: reusing it
means the container defence — the one that stops the twelve "Apps you're gonna love" cards being
mistaken for results — holds here too without being rewritten.
