# modyolo fixtures

**Real** responses, captured on **25/08/2026** from an Italian consumer IP (Wind Tre, AS1267), with
the Chrome mobile User-Agent declared by `ModyoloConfig.DEFAULT_USER_AGENT`. None has been modified:
they are the bytes the server sent, gzipped.

To look at one: `gzcat detail.json.gz | python3 -m json.tool | less`.

| File | URL | Outcome |
|---|---|---|
| `search.json.gz` | `/wp-json/wp/v2/posts?search=minecraft&per_page=20&_embed=wp:featuredmedia&_fields=…` | 200, 180 KB (14 KB on the wire), **20 results with icons** |
| `search-empty.json.gz` | the same with `search=qzxvnpwmkljhgfd` | 200, **empty array**, `X-WP-Total: 0` |
| `search-nsfw.json.gz` | the same with `search=lewd` | 200, **15 results** |
| `search-nsfw-excluded.json.gz` | the same plus `categories_exclude=5410,10637,10638,10639,10640,10644` | 200, **3 results** |
| `detail.json.gz` | `/wp-json/v1/posts/19` | 200, 3 KB |
| `detail-missing.json.gz` | `/wp-json/v1/posts/999999999` | **200** with `"data": null` |
| `download-page.html.gz` | `/download/minecraft-19/1` | 200, 74 KB, **3 variants** |
| `download-ajax.html.gz` | `POST /wp-admin/admin-ajax.php` (`action=k_get_download`, Referer the variant) | 200, 555 bytes |

## The two search fixtures on the same query are not a duplicate

`search-nsfw` and `search-nsfw-excluded` are the same question with and without
`categories_exclude`, and together they prove **two opposite things**, both needed:

- **the filter filters**: 15 results become 3. Without the second fixture, an adapter ignoring the
  parameter would pass every test;
- **the filter is not complete**: the three survivors — `lewd-priestess-quest`, `lewd-mod-2`,
  `my-lewd-therapy` — are in **"Role Playing" (4218)**, not in an adult category. modyolo labels
  badly, and the "Show NSFW content" setting says so in the right words: it hides what the store
  *declares* adult, not what *is*.

Measured the same day and not captured as a fixture because it changes hourly: the **three most
recent articles on the site** were adult visual novels distributed via Patreon, all three in "Role
Playing". That is why `recent` stays `false`.

## `data: null` with HTTP 200

`detail-missing.json.gz` is `{"status":200,"message":"lấy dữ liệu thành công","data":null}`. That
is how modyolo says "this post does not exist", and the HTTP code is **200**. An adapter trusting
the code would return a nameless listing instead of `NotFound`.

The `message` field is in Vietnamese on every response: a detail of the theme's implementation, not
of the content language, which is English.

## The file is written on no page

`downloads[]` of `/wp-json/v1/posts/{id}` is **always empty**, and the HTML of
`/download/{slug}-{id}/{n}` does not contain the URL either. It is delivered by
`POST /wp-admin/admin-ajax.php` with `action=k_get_download`, and modyolo **derives which file to
serve from the `Referer`**: with `/download/minecraft-19` (no index) it answers 200 with twenty
empty bytes, with `/download/minecraft-19/1` it answers with the fragment.

`admin-ajax.php` is the only path their `robots.txt` explicitly puts in `Allow`, and it is the same
request the browser makes. The four-second countdown the page shows is a `setTimeout` running
**after** the response has arrived: there is nothing to wait for.

`ModyoloTestServer` reproduces this rule — answering with an empty fragment if the `Referer` is not
a variant's — because a more permissive double would turn green an adapter that would download
nothing in production.

## The User-Agent is not needed, and is declared anyway

Tried `okhttp/4.12.0`, `curl/8.7.1`, no UA and Chrome mobile: **6,082 identical bytes**. The same
note as an1 applies.

## There is no challenge page

Cloudflare is present (`server: cloudflare`, `cf-ray`) but in **passive CDN mode**: no challenge on
any of the paths the adapter uses, with none of the four clients tried.

## The only change applied to a fixture, and where

`ModyoloTestServer` rewrites `https://files-2.modyolo.com/` to the test server's address, **only in
the AJAX fragment**. It is unavoidable — `preflight` really queries that file, and a unit test does
not touch the network — and it does not alter what is being checked: which link is chosen, how it is
normalised, and what the `HEAD` answers.

## The quarter of the catalogue that is no longer there

Not a fixture but a measurement, and it belongs here because it is the reason `preflight` exists.
120 posts HEAD-probed on 25/08/2026, stratified by age:

| Layer | Alive | Dead (HTTP 500) |
|---|---|---|
| oldest (id 19–418) | 29 | 11 |
| middle (id ~440k–505k) | 25 | 15 |
| most recent (id ~618k) | 40 | 0 |

An earlier measurement put the old layers **at zero** (0/6, 0/12). It was distorted by URL encoding:
the CDN paths contain raw spaces (`/Bloons TD 6/…`) and a client that does not encode them never
even makes the request. With the conditional normalisation in `ModyoloRefs.normalizeFileUrl` the
living ones go **from 2 to 29** on the same sample of forty.

## The package, and why it was verified rather than deduced

`original_download_url` is the Google Play link of the original app, and its query carries the
`packageName`. That this package is **also** the modified APK's is not obvious: a repackaged build
could change it, and the hard block at step 4 of the pre-install pipeline would turn that into "you
can no longer install anything from this store".

Eight APKs downloaded from modyolo and read with `aapt2 dump packagename`, seven of them marked MOD
in their own listing (`Premium / Paid Unlocked`, `Subscribed`, `Premium+ / subscription Unlocked`):
**eight matches out of eight**.
