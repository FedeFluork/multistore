# an1 fixtures

**Real** pages, captured on **25/08/2026** from an Italian consumer IP (Wind Tre, AS1267), with
the Chrome mobile User-Agent declared in `An1Config.DEFAULT_USER_AGENT`. None has been modified:
these are the bytes the server sent, gzipped.

To look at one: `gzcat detail.html.gz | less`.

| File | URL | Result |
|---|---|---|
| `search.html.gz` | `an1.com/index.php?do=search&subaction=search&story=minecraft` | 200, 45 KB, **10 results** |
| `search-page2.html.gz` | the same + `&search_start=2&result_from=11` | 200, **4 results, no overlap** |
| `search-empty.html.gz` | the same with `story=qzxvnpwmkljhgfd` | 200, 32 KB, **0 results and no suggested cards** |
| `detail.html.gz` | `an1.com/2971-telegram.html` | 200, 42 KB — a **program** |
| `detail-game.html.gz` | `an1.com/7112-blockman-go.html` | 200, 43 KB — a **game** |
| `download.html.gz` | `an1.com/file_2971-dw.html` (Referer: the listing) | 200, 13 KB — host `files.an1.net` |
| `download-second-host.html.gz` | `an1.com/file_7112-dw.html` | 200 — host **`files.an1.co`** |
| `download-offsite.html.gz` | `an1.com/file_3854-dw.html` | 200 — the anchor leads to **`bit.ly`** |
| `not-found.html.gz` | `an1.com/9999999-does-not-exist.html` | **404**, 31 KB of complete page |

## The User-Agent is not needed, and is declared anyway

Four clients tried on the same search — `okhttp/4.12.0`, `curl/8.7.1`, **no** UA, and Chrome
mobile: **11,706 identical bytes in all four cases**. an1 does not look at the UA.

The field stays mandatory in the contract and these fixtures were captured with that value, for
the reason written on `StoreCapabilities.userAgent`: adapters get copied from one another, and on
apkmirror OkHttp's default is a guaranteed 403.

## The two fixtures worth most

### `search.html.gz`: ten results, five of which carry an extra class

Modified entries are marked with an extra class. On the first page of "minecraft" it is five and
five. A selector written as an exact attribute comparison — rather than as a class selector —
would lose **half** of them, and it would be exactly the half people use this store for.

### `download.html.gz`: two `.apk` files on the same host

The app's file is in one anchor; next to it, in another, sits `files.an1.net/an1store.apk` —
**an1's own store**, a real `.apk`, on the same host.

It is apkmody's trap with a difference that matters: there filtering the host was enough, here it
is not. The test proves it **positively**, by showing that a generic selector really does return
the decoy. A test that only checked the right result would pass with the defence removed.

The same page also carries a sponsor with the same button class, which is what the host filter —
the **second** check — is for.

## The file hosts are three, not one — and the lesson is about how you measure

`download-second-host.html.gz` is the fixture of a failure found **on the emulator**, not in the
tests. With one host declared, opening one large app and pressing Install gave "The store answered
in an unexpected format": its file is on the second host.

The instructive part is why the initial measurement missed it. The hash-coverage probe had
HEAD-probed six files, that one included, and had worked — but it **printed only the file name**,
not the host. The datum was in plain sight and the column that mattered was absent. Of twelve
listings sampled after the failure, two use the second host, and both are the large ones
(612 MB and 929 MB).

The third host is in the list without having been observed: it costs nothing, and its absence
would cost another "unexpected format".

## Two listings out of twelve have their file outside an1, and stay out

`download-offsite.html.gz`: the download anchor leads to a link shortener, which redirects to
Google Drive. It is not followed, and the outcome is **`NotFound`** — not a parse failure, because
the markup has not changed at all.

The reasoning: an APK from an arbitrary host, on a store publishing neither a package name nor
(for those files) a hash, is the case where the verification pipeline has nothing left to say no
with. The host list is the last structural control remaining.

## There is no challenge page, and that is not an omission

an1 produces none: no Cloudflare, no challenge on any path the adapter uses, with none of the four
clients tried. The missing fixture is the absence of something that does not exist.

What an1 does have, and the other stores do not, is a request budget published in-band on the CDN.
It is not our counter — see the note atop `An1StoreAdapter` — and that is why it has no fixture:
there is nothing to reproduce offline that would be true.

## The SHA-256 is real, and was verified

The file host publishes a checksum metadata header on **some** objects: two of six sampled. Hence
`HashAvailability.SOMETIMES`.

One value was **downloaded and recomputed**: 83,757,788 bytes,
`c62171f089a1eef035642eb7d92388f451307bef9d345e2d70766ee72ea20a3d`, matching. The check was needed
because `x-amz-meta-*` is metadata **defined by the uploader**, not computed by the service —
believing it on trust would have meant comparing the downloaded APK against a number nobody
guarantees. The ETag next to it is multipart and **not** the content's MD5: it would not have been
usable.
