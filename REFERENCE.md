# MultiStore — reference

Detailed notes on how MultiStore is built and why. The short version is in
[README.md](README.md).

Everything stated here as a measurement was measured against the real thing — a live site, a device,
a database on a phone — not inferred from documentation. Where something could not be measured, that
is said instead of guessed.

---

## Contents

- [The three non-negotiable rules](#the-three-non-negotiable-rules)
- [Architecture](#architecture)
- [Adding a store](#adding-a-store)
- [The stores](#the-stores)
- [Anti-bot: the line](#anti-bot-the-line)
- [Installing an APK](#installing-an-apk)
- [Split containers](#split-containers)
- [Which version to offer](#which-version-to-offer)
- [Updates](#updates)
- [Cross-store identity](#cross-store-identity)
- [Search filters](#search-filters)
- [Settings](#settings)
- [Remote configuration](#remote-configuration)
- [Caching](#caching)
- [Diagnostics](#diagnostics)
- [Testing](#testing)
- [Toolchain](#toolchain)

---

## The three non-negotiable rules

### 1. No hardcoded strings, in any language

Every user-visible string lives in `strings.xml` and is added to **all five languages at once**:
`values/` (en, the default), `values-it/`, `values-fr/`, `values-es/`, `values-de/`.

There is no "add it in English and translate later". `TranslationParityTest` fails the build if a key
is missing from one language or if a language carries an orphan key. AGP's `HardcodedText` lint only
inspects layout XML, and MultiStore has none — so the check that matters is
**`MultiStoreComposeHardcodedText`**, a custom lint rule in `:lint-rules` that catches string literals
passed to the user-visible parameters of a `@Composable` (`text`, `title`, `label`,
`contentDescription`, `placeholder`…). Both are at `error` level.

The detector deliberately ignores strings with no letters (`"•"`, `"—"`, `" "`) and composables
annotated `@Preview`, where the placeholder text is the point.

Key naming: `<feature>_<context>_<meaning>` — `search_filters_title`, `myapps_empty_state_message`.
Quantities use `<plurals>` with every form the language requires. Fallback is `en`; the initial locale
is the system's. The per-app locale uses `AppCompatDelegate.setApplicationLocales` plus
`res/xml/locales_config.xml`.

### 2. Every configurable feature has a Settings entry

Adding one is four things, not three:

1. a field in `core/datastore/src/main/proto/settings.proto`;
2. an entry in the Settings registry;
3. the five translations of label and description;
4. **a row that names the key**, actually drawn on screen.

The registry is not a parallel list — it is the source. The rows take a `SettingKey` and read their
label and description from `SETTINGS_REGISTRY` rather than writing their own `stringResource`. Before
that, the two could diverge with nothing saying so, and with in-screen search that divergence stops
being theoretical: the filter compares the registry's text, so a row showing something else would
disappear when searching for a word the user has in front of them.

Three guardrails close the triangle: `SettingsCoverageTest` (proto ↔ registry, both directions),
`SettingsScreenCoverageTest` (registry ↔ screen — and it looks for the *position*
`key = SettingKey.X,`, not the mere mention, because naming a key in a section list is not drawing
it), and `TranslationParityTest` for the strings.

#### The proto3 zero-value trap

proto3 has no explicit defaults: the zero value of a field **is** the default. That makes field naming
and enum ordering behavioural decisions, not style.

- A `bool` called `verify_hash_when_available` would start **off** — with verification disabled —
  while anybody reading the name assumes the opposite. It is `allow_unverified_hash`.
- `UpdateInterval` puts `DAILY` first and not in natural order: written "manual, 6 h, 12 h, daily,
  weekly", the zero value would be `MANUAL`, that is update checking off out of the box.
- `ChallengeStrategy` puts `BALANCED` first; with `CONSERVATIVE = 0` the app would start with no
  WebView escalation at all.
- `InstallerPreference` puts `AUTOMATIC` first: any other value at position zero would start the app
  forcing a channel that may not exist on the device.
- `catalog_retention` is an enum where a number would be more natural, because **two** legitimate
  choices would land on zero: "zero days" and "forever" are both things a user may ask for, and
  neither can live in the value that means "never written". *When a legitimate choice would land on
  zero, the field cannot be a number.*

The criterion was never "booleans are written negatively" — it is **the zero value must be the safe
behaviour**. `show_nsfw_content`, `diagnostics_log_enabled` and `allow_preview_channels` are written
positively precisely because for them "off" *is* the prudent behaviour, and off is zero.

Numeric fields hide the same trap where it is hardest to see. `search_timeout_seconds` at zero would
mean "wait for no store at all"; `image_cache_max_mb` at zero would mean "no icons on disk", which is
worse because it is *plausible* — an app that re-downloads every icon does not look broken, it looks
slow. Zero therefore means "never written" and the translation to the domain substitutes the default;
the minimum of each range must stay greater than zero, which is what makes "never written" also
out-of-range.

`SettingsDefaultsTest` covers all of this: it asks what the domain translation answers when the
DataStore is empty. Reordering an enum turns it red.

### 3. Every UI component works in light and dark

Colours come **only** from `MaterialTheme.colorScheme` and the `:core:designsystem` tokens. Never
`Color(0xFF…)` in a `:feature:*`, never a fixed colour in a composable. Every screen has a Roborazzi
screenshot test in both themes; `ScreenshotCoverageTest` fails if one has only one. The theme has
three states — `SYSTEM` (default), `LIGHT`, `DARK` — and dynamic colour (Android 12+) is checked too.

---

## Architecture

### Modules

```
:app                    DI root, NavHost, adapter multibinding
:core:model             pure data classes — NO Android dependency
:core:common            Result/AppError, dispatchers, RateLimiter, CircuitBreaker, normalisation,
                        cross-store identity (IdentityMatcher, AppKeys, AppAggregator)
:core:network           OkHttp, interceptors, Jsoup helpers
:core:database          Room
:core:datastore         Proto DataStore (settings)
:core:designsystem      theme and tokens
:core:ui                shared components
:core:domain            use cases (+ NetworkConditions: metered or not)
:core:data              repositories — the only module that sees Room, DataStore and the adapters
                        together. StoreRegistry, StoreIndexRepository, SearchRepository,
                        AppDetailRepository, CrossStoreRepository, InstalledAppsRepository,
                        StoreHealthRepository, DownloadRepository, InstallRepository
:core:installer         Installer (session + privileged shell) + pre-install verification
                        + split-container opening and split selection
:core:download          download engine
:core:updates           periodic update checking: worker, scheduling, notification
:core:remoteconfig      signed index.json / parsers.json
:core:challenge         the escalation rungs that need Android: silent WebView (rung 3).
                        :core:network is pure Kotlin and cannot see them
:store:api              StoreAdapter + IndexedStoreAdapter + capabilities + errors
                        (+ testFixtures: StoreAdapterContractTest)
:store:common           scraping helpers: HtmlPage (a selector that finds nothing fails instead of
                        yielding an empty string; closest walks up to ancestors; dataOrNull reads a
                        <script>'s content), mapRowsOrFail (rows found and none readable =
                        ParseFailure, not zero results), TextValues, Urls, PageFetcher, error
                        translation
:store:<name>           one module per store
:feature:<name>         home, search, appdetail, storelisting, myapps, settings, webviewdownload

:core:testing           shared test infrastructure — testImplementation only
:lint-rules             custom lint checks (hardcoded strings in composables)
:guardrails             the tests that verify the repository, not the app's behaviour

:tools:index            the pipeline that produces index.json — NOT in the APK
```

The last three were added because **a rule no test can violate is worth more than a written rule**.
`:lint-rules` exists because `HardcodedText` does not see Compose; `:guardrails` because the
constraints on translations, settings and screenshots concern the file tree rather than a single unit
of code; `:core:testing` because without a common place the screenshot-capture logic — and the
repository test doubles every ViewModel needs — would end up copied into seven feature modules.

`:core:updates` is a module of its own for the same reason as `:core:download`: WorkManager, a
notification channel and `POST_NOTIFICATIONS` belong to whoever uses them, not to `:app`.

### Dependency rules — verified, not just written

`./gradlew checkDependencyRules` checks all of them, one module at a time, and is wired into `check`.
Each module inspects only itself, so the check is configuration-cache compatible.

- **`:core:*` and `:feature:*` NEVER depend on a concrete `:store:<name>`.** Only on `:store:api`.
  Concrete adapters are wired in `:app` via Hilt `@IntoSet` — and in `:tools:index`, which is the
  pipeline and does not go into the APK. The exception is written into the guardrail: an exception
  declared in a guardrail is one somebody decided, one the guardrail cannot see is an oversight.
- `:store:<name>` depends only on `:store:api`, `:core:model`, `:core:network`, `:store:common`. It
  does not see Room, Compose or `:core:data`.
- `:core:model`, `:core:common` and `:core:network` stay pure Kotlin (testable on the JVM without
  Robolectric). That is why `:core:challenge` exists: rungs 2–4 of the escalation ladder need Android,
  and putting them in `:core:network` would have forced Robolectric onto a module that tests in
  seconds.
- A `:feature:*` never depends on another `:feature:*`.

---

## Adding a store

1. New `:store:<name>` module with the same structure as the existing ones.
2. Implement `StoreAdapter`. Declare `StoreCapabilities` **honestly**: a capability declared `true`
   and not populated fails the contract test.
3. Extend `StoreAdapterContractTest`.
4. Add fixtures under `src/test/resources/fixtures/<name>/`: **real pages, gzipped**, with a
   `README.md` giving URL, date, response code and the client used. Search, detail, download, **no
   results** and a challenge page are needed — and if the store does not produce one of those, write
   *why* instead of inventing it. The "no results" fixture is the most valuable one: on apkmirror it
   has 38 sidebar rows with the same markup as results; on apkcombo it needs a token that is not a
   substring of any title.
5. Register the adapter with `@IntoSet` in `:app`'s Hilt module.
6. **Add the store description in all five languages** (`storeDescriptionRes` in `:feature:settings`).
   Not the name: that is a trademark and is not translated. Enabling does **not** go into
   `settings.proto` — it lives in Room, the `enabled` column of the `stores` table, which is what
   `SearchRepository` reads. A second copy in the DataStore would be a value that diverges. The
   guardrail is `StoreCatalogTest`.
7. Add base URL, selectors, rate limit and TTL to a `@Serializable` `<Store>Config` with **compiled
   defaults**. No CSS selector in Kotlin code. **The `@Provides` in `StoreModule` must go through
   `parsers.override(StoreId.X, XConfig(), XConfig.serializer())`**, not return the bare
   configuration: that is what makes the store repairable by publishing `parsers.json`, and
   `StoreCatalogTest` requires it. The explicit serializer is not verbosity — a configuration without
   `@Serializable` then fails to compile, whereas with reflective resolution it crashed at first
   launch.
8. Add a `@Tag("canary")` test in `src/test/` and the step in `.github/workflows/canary.yml`. The
   failure message must distinguish **markup changed / blocked / rate limited**: those are three
   different jobs, and whoever reads the issue at 4 a.m. has to tell from the first line.
9. Update the store table below.

No step requires touching the core. If you find you have to, the contract in `:store:api` is
incomplete: fix the contract, do not special-case.

This has been verified four times, adding stores in pairs and singly: each entered with two `@Binds`
and two `@Provides` in `StoreModule`, with no `:core:*` or `:feature:*` file touched *to make them
work*. What did require core work was a **new feature** none of them brought alone — the adult-content
filter (`SearchFilters.includeNsfw`, `FilterCapability.NSFW_CONTENT`). That distinction is the one
that matters: if the contract widens because *an adapter does not fit*, the contract is incomplete; if
it widens because the app does something more, that is ordinary development.

---

## The stores

Reachability and selectors were **measured by direct observation**, from a consumer connection rather
than a datacentre — that is the app's real environment, and from a datacentre everything behind
Cloudflare gives false results.

| Store | Host | Risk | Download | Binding operational notes |
|---|---|---|---|---|
| **f-droid** | `f-droid.org` | 🟢 low | direct, 1 hop | Certificate pinned. **108 categories** published (not ~17 top-level) and `app_count` never declared: the sync does the counting. Search comes from the **local index**; the remote API is on a *separate host*, capped at 10 results, ignores paging and gives neither packageName nor version — useful only before the first sync. Three `.zip` entries must be filtered out. `antiFeatures` lives **only** under `versions.<sha>`. `suggestedVersionCode` ignores the signer, so it is **not** an update oracle. |
| **apkcombo** | `apkcombo.com` | 🟡 medium-low | direct | No anti-bot over ~40 requests: `/download/apk` is byte-identical with a Chrome UA, with curl's, and with none. **301s must be followed** (non-canonical slug). **Search pagination does not exist:** `?page=2` and `?page=3` return the same 20 results. Matches by substring, so "no results" is rare. No published hash. XAPKs exist and **the extension lies**: the R2 object is `….apks`, the `content-disposition` calls it `.xapk`, and inside is an XAPK with `manifest.json` `xapk_version` 2 whose base is `<packageName>.apk`, not `base.apk`. The link is `/r2?u=<signed url>`: decode the query rather than following the redirect. R2 signature valid 4 h. `versionCode` only in the info table, inside `span.blur`. |
| **apkmirror** | `www.apkmirror.com` | 🟡 medium | direct | The second-best data quality after F-Droid. `okhttp/*` and `curl/*` → **403 from 153 B**; a Chrome mobile UA → 200: the UA is everything. **`Crawl-delay: 3` is real — it answers 429.** Three-level chain: app → release → variant → interstitial → `download.php` → 302 to R2 (1 h). The variant page gives **the file's SHA-256** *and* **the certificate's SHA-256** in two distinct sections of the `#safeDownload` modal: swapping them is the easy mistake. The `versionCode` is **per variant, not per release**. Bundles are `.apkm`. The RSS feed is the only new-releases source publishing the **developer**, and it carries the icon as the first `<img>` inside `<content:encoded>`'s CDATA — not in an `<enclosure>`, which does not exist. |
| **apkmody** | `apkmody.mobi` | 🟡 medium | direct | The first store here that redistributes modified APKs. `apkmody.com` is on the **blocklist, not a fallback**: deep paths 301 elsewhere. Search is **fuzzy, not substring**, and has **no pagination**. **The `.rating` block shows 4 of 5 stars on every app measured: it is decoration, not a rating.** The search-card image is a *cover*, often a placeholder — never an icon; the real icons are on `/popular`. `versionCode` lives **only** in the CDN file name, verified with `aapt2` against the downloaded APK. The file sits on a specific CDN host: **the host is the only thing distinguishing it from the advert** sitting next to it in the same list with the same markup. The declared size is rounded, so `expectedSize` is null. |
| **modyolo** | `modyolo.com` | 🔴 very high | direct + **preflight** | **The only store that labels adult content**, with six WordPress categories and server-side `categories_exclude` — but the three most recent posts on the site were adult visual novels filed under "Role Playing": **the filter hides what the store declares, not everything**. **Dead binaries ~22%** (measured over 120 posts) → a HEAD preflight, where a 500 is `Success(false)` and not a store fault. Half of an early estimate was skewed by encoding: the CDN URLs carry raw spaces, and without conditional normalisation 28 of 40 looked dead. Two APIs: `wp/v2/posts` searches and really paginates; `v1/posts/{id}` gives the detail. **`data: null` with HTTP 200 = NotFound.** The file is on no page: it comes from a `POST` to `admin-ajax.php` **with the variant's Referer**. packageName from the Play link, **verified on 8 APKs (7 of them MOD): 8/8**. |
| **an1** | `an1.com` | 🔴 very high | direct, 2 hops | DataLife Engine, not WordPress. **0 packageName site-wide** and **0 versionCode** → identity by title + developer only, and `UpToDate(comparable = false)`. **But a hash exists on some objects**: `x-amz-meta-checksum-sha256` on the CDN HEAD, verified by downloading 83,757,788 bytes and recomputing. The ETag is multipart and is not the MD5. Search really paginates, with **two** parameters, 10 per page. MOD entries carry `class="item_app mod"`: an exact attribute match loses half of them. Next to the real download sits `an1store.apk` **on the same host** — the host alone does not tell them apart. **`x-ratelimit-*` is a shared budget, not ours** (see below). |
| **pdalife** | `pdalife.com` | 🔴 very high | user-assisted only | The only store publishing iOS, PSP and Android in the same list. Cloudflare in **passive CDN** mode: no challenge on reads. **Search is not `/suggest/`**: that endpoint exists, is JSON and robots-allowed — and returns **ten results for every query, including ones with no matches**. The HTML search is used instead: 20 per page, declared `data-max_page`, zero results when zero. Queries must be slugified first. packageName **exists** but only inside `.game-download__stores`: the page's first `play.google.com` link is an advert **17 times out of 17**. No hash, no versionCode — `data-version_id` is not a versionCode, it grows site-wide. **Never positional selectors** (advert slot order is randomised server-side). reCAPTCHA v3 on hop 2, where **two of the three buttons are adverts and one is a real `.apk`**. The RSS feed has **5 entries out of 100 dated in the future**, up to 2029. |
| **uptodown** | `en.uptodown.com` | 🟡 medium | user-assisted (download only) | Third for data quality, and the only assisted store publishing a hash. Use the **language subdomain**: `www` serves Spanish. Search returns **one page only**: `?page=2` gives the same 36 apps in a server-randomised order. Listings carry **SHA-256 per file**, packageName, size, date, ABI and a `minSdk` written **`Android + 5.0`** — with the sign *before* the number. **No `versionCode` anywhere on the site**: `data-version-id` is the file's id. "**Certificate signature**" is **MD5** (32 hex) despite the `icon-40-sha256` icon. Info rows have **three cells** and the first is the icon: a bare `td` reads empty and silently drops the row. An empty search has **no `#content-list`** and 12 recommendation cards with identical markup in its place. Download: a `<button>` plus an interaction-only Turnstile. Their ToS forbid automated access, which makes the user-driven path also the most defensible one. |
| **liteapks** | `liteapks.com` | 🔴 very high | direct (+ token) | With the real client — OkHttp, HTTP/2, Chrome mobile UA — the listing answers **200**; `curl` on HTTP/2 gets 403 `cf-mitigated: challenge`, and **OkHttp forced to HTTP/1.1 does too**. With a `curl/8.7.1` UA even OkHttp on HTTP/2 gets 403 — **the UA decides on its own**. Search: 18 per page with `paged` honoured; the declared total **saturates at 60**. Half the listing is read from the `SoftwareApplication` **JSON-LD** (31/31), not from the Tailwind classes. **packageName on 26 listings out of 31**, and only inside `.app-stats`: an advert Play link is on **31 out of 31** and on 5 it is the only one, so "the first link" gives **another app's package** one time in six. No hash, no versionCode. The file page comes in **two** markup shapes, and the version sits in two places. The file is behind a `?token=` plus a Referer (see below). |

### A measurement taken with one client is not a measurement of your client

The most expensive finding in this project, and it generalises. On apkmirror, with **curl**, release
pages answer `403 cf-mitigated: challenge` on HTTP/2 and `200` on HTTP/1.1 — four URLs out of four,
twice each. From that came the conclusion "apkmirror must be queried over HTTP/1.1", and with it a
configuration field, a value in `NetworkTier` and a branch in the download engine.

With **OkHttp** — the client the app actually ships — the opposite holds:

| Client | HTTP/2 | HTTP/1.1 |
|---|---|---|
| curl | 403 challenge | **200** |
| **OkHttp** | **200** | 403 challenge |

Retried with the full `Accept` / `Accept-Language` / `Sec-Fetch-*` / `Upgrade-Insecure-Requests` set
and with the parent page's Referer: no difference. Cloudflare does not look at the HTTP version in the
abstract but at the **coherence between the TLS handshake and the application layer**.

The pin was removed. **Whoever verifies an endpoint with `curl` is measuring `curl`**: confirmation
has to come from the real client, which is why the canary uses the adapter and not a script.

The same inversion happened again on liteapks, and there it *removed* work rather than adding it: the
plan said the detail page needed the silent-WebView rung, which is true for `curl` and false for us.
Rung 1 (protocol fallback) on that store *provokes* the challenge instead of resolving it.

**Third formulation:** a correct measurement with the wrong client can *add* work that is not needed
or *cause* a component to be written that nobody consumes. **Fourth formulation, and it is not about
networking:** a measurement taken at the wrong moment is not a measurement. A probe on
`Android/obb` answered *yes* on a process forked before the system restricted its mount namespace —
and that was the answer one hoped for; repeated after a device reboot, every combination answers no.
*A measurement that confirms what you wanted to hear gets repeated before being believed.*

### A published number is not necessarily a number about you

an1 publishes `x-ratelimit-limit: 1394` and a decreasing `x-ratelimit-remaining`, and the plan asked
for a response-driven `RateLimiter`. Re-measured with the right questions, the requirement collapsed:
the headers **do not exist on `an1.com`** at all — only on the CDN host, where we make one HEAD and one
GET per download — and the counter **is not ours**. Three identical HEADs left `remaining` unchanged at
1355; three on another object saw it drop by three at a time while we made one. Between two
measurements it went **up**, from 1346 to 1355. It is a shared budget that recharges.

Slowing our single user down because the world is downloading would be worse than useless. The real
signal is the **429 with `Retry-After`**, which `StoreErrors.fromResponse` already interprets.

### Before adopting an endpoint, ask it the question it must not be able to answer

pdalife's `/suggest/?query=` is JSON, robots-allowed, and returns ten results for
`?query=zzqxwvnbtklmj`. It never comes back empty, does not paginate, and carries no version or
rating. In an aggregator that merges several stores, a source answering ten irrelevant results to
**every** query is not a source: it is noise on every search, forever.

An endpoint that answers `telegram` well says nothing; one that answers a nonsense string well has
just told you everything. The same family showed up three more times in one hour, all answering 200:
`an1.com/popular/` is **byte-for-byte the homepage**, so is `pdalife.com/android/`, and
`liteapks.com/feed/` returns the homepage as HTML where an RSS feed was expected. A fourth variant is
more insidious because the 200 is sincere: apkcombo's `/top-apps/`, `/trending/` and `/new-apps/` are
real, distinct pages with the right headings — and **zero links to a listing**, because JavaScript
writes the list.

The question is not "does it answer?" but **"does it answer differently from how it would answer if it
did not exist?"** An `md5` against the homepage costs a second and closes three cases out of four; the
fourth is closed by counting links, not bytes.

### On a store that lives off advertising, "the first one" is never the answer

Three findings from the same day on the same store: a Telegram listing carrying **five**
`play.google.com` links, four of them the same advert (reading "the first" gives that package **17
times out of 17**, and the real link, where present, is the last); a
`<div class="js-banner" data-type="app_download_buttons">` — an advert that calls itself "download
buttons" in its own attributes; and a download page where two of three `a.b-download__button` are
adverts, one of them **a real `.apk`**.

The rule that follows goes beyond that store: **anchor to the container the store's template uses for
the real thing**, not to "the first element of that type on the page". And the verification is not
that the right value comes out: it is that the **fallback** really produces the wrong one. A test that
only checks the outcome would pass with the defence removed.

---

## Anti-bot: the line

The boundary is precise, not vague:

- ✅ **Allowed:** setting a realistic User-Agent (standard practice for any app); using Cronet (*it is*
  the Chromium stack — an authentic fingerprint by construction); loading a page in a **WebView** so
  that it genuinely executes the JS challenge; reusing the resulting `cf_clearance` in the `CookieJar`.
- ⛔ **Forbidden:** third-party captcha-solving services; JA3/TLS forgery libraries to impersonate a
  browser we are **not** running; IP rotation or proxy pools to get around a block; automating the
  solution of a captcha meant for a human.

The criterion: **actually doing** what the site asks is legitimate; **pretending** to have done it is
not. Where a download requires a real human tap (interactive Turnstile, reCAPTCHA), the answer is
`UserAssisted` → the user performs the tap.

Also out of scope, permanently: mass crawling, and speculative prefetching. Opening an app's page does
not query the other stores.

### A token that attests to nothing is not a protection to circumvent

The concrete case is liteapks, and it is the first where the "really do it / pretend" criterion had to
rule on something that *resembles* an obstacle.

The worker in front of the download host answers `403 "Access is not allowed."` to anybody not
carrying **two** things: a Referer from the site and a `?token=`. The token is `btoa(btoa(expiry))` —
a Unix timestamp in base64, twice — and the theme computes it at click time, in plain sight, inside
its own `site.js`.

What the worker actually checks, measured:

| token | outcome |
|---|---|
| expiry in 3 hours | 200 |
| expiry in **10 days** | **200** |
| expiry already past | 403 |
| non-numeric text | 403 |
| single base64 | 403 |

There is no signature, no key, no secret: it is **an expiry declared by the client**, which the worker
compares against the clock. Computing it is doing what the site asks, not pretending to — **there is
nothing to pretend, because the token attests to nothing**.

**Where the line would move, and it is worth writing down before it happens:** if the token became an
HMAC with a server key, computing it would require either extracting that key (forgery — forbidden) or
executing their JavaScript (that is what rungs 3 and 4 exist for). In neither case would the right
answer be a cleverer parser.

**The question that separates the two cases:** *what does presenting this value prove?* A
`cf_clearance` proves a challenge was executed; a reCAPTCHA token proves a human interacted. An expiry
in base64 proves nothing — it is a parameter, not evidence.

### The escalation ladder

An adapter never handles challenges itself: it asks `:core:network`, which applies the rungs in order
and stops at the first that succeeds. The rung reached is recorded in `health_events` for diagnosis.

| # | Resolver | Technique |
|---|---|---|
| 0 | `PlainResolver` | OkHttp + **an explicit per-store UA** — the contract test fails if an adapter does not declare one. OkHttp's default is a guaranteed 403 on apkmirror |
| 1 | `ProtocolFallbackResolver` | retries forcing **HTTP/1.1**. Not a universal shortcut — see the measurement above |
| 2 | `CronetResolver` | the **Chromium** network stack: an authentic fingerprint by construction |
| 3 | `WebViewSilentResolver` (`:core:challenge`) | an off-screen WebView that **executes** the JS challenge and transfers `cf_clearance` into the `CookieJar`. Cloudflare's Managed Challenge is automatic → **no user tap** |
| 4 | `UserAssistedResolver` | a visible WebView, the user taps. The always-available final fallback |

Rung 3's *mechanics* are proven — the WebView opens, executes, the cookies are read, the jar transfers
them and OkHttp retries. What could **not** be proven is a real Managed Challenge solved by our
WebView, because from this network no browser engine we ship gets challenged. It is there for whoever
sits in a worse reputation band, and costs nothing until somebody is challenged. That limitation is
stated rather than glossed over.

Two things that would fail silently if wrong, and are tested:

- **the WebView's User-Agent is the store's.** A `cf_clearance` is bound to the UA that obtained it:
  with two different UAs the cookie comes back valid and useless, and the symptom — the WebView passes,
  the retry does not — sends you looking everywhere but there;
- **a HEAD does not take the WebView to the file.** A WebView can only do GETs: pointing it at a HEAD's
  URL would mean **downloading** the object the HEAD only wanted to interrogate. For anything that is
  not a GET, the root is loaded instead.

---

## Installing an APK

### Permissions

| Permission | Note |
|---|---|
| `REQUEST_INSTALL_PACKAGES` | manifest + `canRequestPackageInstalls()` + a deep link to `ACTION_MANAGE_UNKNOWN_APP_SOURCES` |
| **`REQUEST_DELETE_PACKAGES`** | via `PackageInstaller.uninstall`. **Not** `DELETE_PACKAGES`, which is `signature\|privileged` and is never granted to a normal app — with `targetSdk 36` this one is required, or `uninstall()` throws `SecurityException` |
| `QUERY_ALL_PACKAGES` | legitimate: this is a store, and it is not on Play |
| `POST_NOTIFICATIONS` | **at runtime, on first real use** — not at the splash screen |
| `FOREGROUND_SERVICE` + `_DATA_SYNC` | downloads in progress |
| `ACCESS_NETWORK_STATE` | for `isActiveNetworkMetered`, that is "sync on its own only on an unmetered network". Without it that read throws |
| `moe.shizuku.manager.permission.API_V23` | talking to Shizuku. Not a system permission: the Shizuku app defines it, so where that is absent it simply stays ungranted. It goes together with the `ShizukuProvider` — without which `pingBinder()` answers `false` forever and nothing would say why |
| ~~`RECEIVE_BOOT_COMPLETED`~~ | **not declared, and not an oversight.** WorkManager reschedules its own periodic work after a reboot, with its own boot receiver and its own database |
| ~~`MANAGE_EXTERNAL_STORAGE`~~ | **not declared** — see [Expansion files](#expansion-files-and-the-permission-that-buys-nothing). With it **granted**, `Android/obb/<other package>` stays inaccessible |

Permissions are declared by **the module that needs them**, not by `:app`: that way they cannot be
forgotten when the module is added elsewhere, and it is visible from there why they exist.

No storage permission: staged APKs go into **`filesDir`**, not `cacheDir`. Both are app-private, but
the system can empty the cache at any moment — including between verifying an APK and committing it
into an install session — and it does so exactly when the device is full, which is while something is
downloading.

### The pre-install pipeline — mandatory, identical for every store

In order, with no shortcuts:

1. size compared against the expected one;
2. **SHA-256 computed in streaming** during the download, compared against the store's published hash
   where there is one;
3. **archive read and verified with `apksig`** → `packageName`, `versionCode`, `minSdk`, signer
   fingerprints. **Not** `getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)`, for two measured reasons:
   that flag exists **from API 28** while `minSdk` is 26 — on 26 and 27 the signer would always come out
   absent and steps 3 and 5 would become *silent* no-ops — and `getPackageArchiveInfo` **reads** the
   certificates without verifying the archive matches them, whereas `ApkVerifier` checks every entry's
   digest and the v1/v2/v3 schemes;
4. **`packageName` must match the one the listing declared → hard block on mismatch.** This check is
   not configurable and not bypassable: it is the defence against installing the wrong APK. Where the
   store does not publish a packageName the comparison cannot be made, and the UI has to say "not
   contradicted", not "verified";
5. **signature.** If the package is already installed, the fingerprint must match the installed one
   (read from the `PackageManager`, not from our database); otherwise the offer is an explicit
   "uninstall and reinstall (you lose the data)". If it is **not** installed, the comparison is with the
   signer the store declared: without that, the first installation — the one that establishes which
   signing chain you are tied to — would be the only unverified one. `signer` is a **list**, and next to
   it is the **lineage** declared by scheme v3: the comparison looks at both. Looking only at current
   signers would report "different signature" on every key rotation;
6. **anti-downgrade**: the `versionCode` is compared with the installed one. Installing an older
   version is the classic way to reintroduce an already-fixed vulnerability;
7. record `installed_apps` with source store, version, hash and installer used.

An eighth step covers splits: each must be a **signed** APK belonging to the same app as the base —
same package, same version, same signers.

Only two switches loosen any of this, `allow_unverified_hash` and `allow_signer_mismatch`. Neither
skips the check: `allow_unverified_hash` still computes and compares the hash, and only stops the
mismatch from blocking — the outcome reports it in `Ok.hashWasVerified`, because skipping it would make
"the hash does not match" indistinguishable from "the store does not publish one". **The packageName
match has no switch and must not have one.**

**The verified file and the installed file must be the same file.** Verifying in staging and then
handing the file to `PackageInstaller` opens a TOCTOU window: the installers compute the SHA-256
**while** writing the bytes into the session and abandon it before commit if it does not match.

**Known limitation, not to be forgotten:** for sources that redistribute modified APKs there is no
original developer signature to compare against. The pipeline protects against package substitution,
not against tampering upstream.

### Installer channels

`Installer` has three implementations: `SessionInstaller` (always available, user confirmation) and
the two silent ones, which are **the same `ShellInstaller` with two different shells`.
`InstallerSelector` picks at runtime honouring `installer_preference`; the chain is
`ROOT → SHIZUKU → SESSION`.

**The app must work fully with `SessionInstaller` alone.** No feature may require Shizuku or root as a
prerequisite: at most it may only be available with them, and in that case the UI says so.

What distinguishes root from Shizuku is **how the process is born** — `su -c` on one side,
`Shizuku.newProcess` on the other — not what happens inside it. From there the protocol
(`pm install-create` → `install-write` → `install-commit`) is identical. That protocol is also the only
**testable** piece: `su` and Shizuku exist on no emulator image, while the protocol tests on the JVM
with a fake shell. Three things it must do:

- **the APK travels on standard input** (`pm install-write … -`), not as a path. The staged file is in
  `filesDir`, private to the app: Shizuku's `shell` user **cannot read it**, and the obvious way out —
  copying it somewhere world-readable — would expose the freshly verified file exactly between
  verification and commit;
- **the SHA-256 is computed on those bytes as they leave**, and a mismatch means `install-abandon`
  instead of `install-commit`;
- **no `-p`**: it reads as "the package is this", and in `PackageManagerShellCommand` it actually puts
  the session into `MODE_INHERIT_EXISTING`, turning it into an addition of splits to an already-installed
  app. `-i <our package>` *is* needed: without it the installer of record is `com.android.shell` and "My
  apps" loses the link to the store.

A package name interpolated into a shell line goes through an alphabet check (`[A-Za-z0-9_.]`), because
those names come from HTML downloaded from a store.

`su` is measured by executing it, and it costs: there is no way to ask a root manager "would you grant
it?" without asking. `RootShell.isAvailable()` tries **once per process, and only if a `su` binary
actually exists** — on an unrooted device that is five `File.exists()` calls and no process. Shizuku
answers both questions without showing anything, which is why Settings can list the channels without
raising dialogs; the permission is requested **only when the user picks that channel**, with the app in
the foreground.

`select` and `selectSilent` are two questions, not a boolean. "Who installs?" cannot fail —
`SessionInstaller` is always there — so `select` does not return null. "Who installs **without asking
anything**?" can perfectly well have no answer: `selectSilent` is nullable, and its caller is the
periodic check. Degrading silently there would mean a confirmation screen launched from the background —
which since API 34 does not start at all — that is, an installation that does not happen and nothing
saying so.

### Failure codes

`AppError.InstallFailed` carries `PackageInstaller`'s `statusCode`, and the UI translates all seven
outcomes rather than flattening them into "the system refused the installation". True for all seven and
useful for none: they lead to seven different actions, and two of them — **space** and **signature
conflict** — the user fixes in thirty seconds if somebody tells them.

`STATUS_FAILURE_BLOCKED` is the only outcome whose sentence names the manufacturer. That is the case
where the refusal comes not from Android but from something above it — a device policy, a system
antivirus, a ROM feature — and there "the system refused" sends people looking in Android's settings,
where there is nothing to change. **Per-OEM steps are deliberately absent:** every skin has its own
entry in its own menu, none of them verifiable from here, and a list of menu paths copied from a forum
ages with every ROM update and has no test to notice.

Opening the permission screen cannot bring the app down: towards an intent nobody resolves,
`startActivity` throws `ActivityNotFoundException` — and it would be the app crashing on somebody who is
already having the problem that button is meant to fix. `InstallSources.open` tries three rungs — the
screen for this app, the app's entry in the system settings, and **saying so** — because the ROMs that
get in the way of that permission are exactly the ones that may have moved the screen.

---

## Split containers

`ArtifactType` is not decorative: marking a `.xapk` as `APK` produces a file handed to
`PackageInstaller` that it refuses. The type is still a *declaration*, though: whoever opens the file
looks **inside**, because the extension is written by somebody else.

### Two formats, measured by opening two real ones

| | XAPK (apkcombo) | APKM (apkmirror) |
|---|---|---|
| compression | **`store`** | **`deflate`** |
| base | `<packageName>.apk` | `base.apk` |
| splits | `config.<abi>.apk`, `config.<dpi>.apk` | `split_config.<abi>.apk`, `split_config.<dpi>.apk` |
| metadata | `manifest.json`, `xapk_version` `"2"`, `split_apks:[{file,id}]` | `info.json`, `apkm_version` `5`, `pname`, `versioncode` |
| extras | `icon.png`, an installer URL | `icon.png`, an installer URL, **`META-INF/` JAR-signed by APKMirror** |
| size | 238 MB, of which **180 useful** on arm64 | 286 MB compressed, **624 uncompressed** |

Three consequences no documentation guaranteed:

1. **the base is not named the same way in the two formats** — hence it is read from
   `split_apks[].id == "base"` where the container declares it, and from the name only where there is no
   metadata;
2. **`apkm_version` is a number and `xapk_version` a string**, and the same goes for `versioncode`
   against `version_code`. Reading only one form gives `null` on the other format;
3. **the third format is recognised and not opened.** A `bundletool` APK Set has `toc.pb`, a protobuf no
   dependency here can read: guessing the splits from their names would give an installation that looks
   successful and is missing something. A refusal that names the format is better.

**The extension lies; the content decides.** An APK is recognised because the zip has an
`AndroidManifest.xml` **at the root** — which no container has and every APK has by construction. And a
zip that will not open is not a broken container: it is a file to verify. The first draft answered "not
a container this app can open" on any exception, and an existing test contradicted it — a truncated
download stopped producing the verification pipeline's message and started producing one about a format
the file has nothing to do with.

### Not everything inside gets installed

| kind | rule | why |
|---|---|---|
| base | always | without it there is nothing to install |
| ABI | **one**, the first of `supportedAbis` the container has | that is the device's preference order, and the first is native |
| density | **one**, the smallest bucket ≥ the device's | below is blurry, above is waste — and blurry is worse |
| language | **all** | there is no on-demand channel here: somebody changing system language would be left without, and with no way to fix it |
| module / unclassified | **always** | one split too many costs space; one too few is a missing part of the app, and the symptom arrives inside the app where nobody connects it to the installer |

`densityDpi` is in `DeviceProfile` for this, and **zero is not a density**: it is "I do not know", and
there the choice falls back to the largest bucket. Leaving it to a `>= 0` comparison — true for every
bucket — would pick `ldpi`, the same trap as the numeric proto3 fields seen from another angle.

**When no ABI matches, the outcome is a refusal, not a partial installation.** An app missing its `lib/`
installs perfectly well and dies at the first `System.loadLibrary`. A container with **no** ABI splits at
all is normal, though — the app has no native code — and there is nothing to match.

### Base and splits go into the same session

`InstallRequest` carries a **list**, and both installers write one file at a time into the same
`PackageInstaller` session: base and splits separately would be two installations, and the system would
refuse the second. Four things that are not visible from the code:

- **we do not say which one is the base.** `PackageInstaller` reads each written file's manifest and
  decides for itself; the name passed to `openWrite` is just a file name inside the session. It **must**
  be unique, though, because two writes with the same name are one overwriting the other — and the
  outcome would be an app missing a split, with no error;
- **the name comes from the extracted file, not from the zip entry**: an entry can live in a folder
  (`apks/base.apk`) and `openWrite` does not accept a path;
- **the digest is compared APK by APK and immediately**, not at the end: a mismatch on the first split
  abandons the session before writing the two hundred megabytes that follow;
- **the digests are the ones computed at extraction.** Recomputing them before writing would close the
  wrong window: the one to close is between the file being written to disk and it entering the session.

The store's declared hash is compared against **the delivered file**, that is the container: it would
never match the base, and the diagnosis would be "this store publishes wrong hashes".

The extracted pieces are **derived data** and are thrown away when the installation finishes: the
container is still there and reopening it costs less than keeping a second copy — 250 MB on a large app.

Verified on a device, which is the only place a multi-APK session can be tested: a 114,333,279-byte APKM
downloaded, three files extracted out of twelve, installed in a single session. `dumpsys` reports
`splits=[base, config.arm64_v8a, config.xxhdpi]` and `installerPackageName=com.multistore.debug`, and
**the app starts** — which is the proof that was needed, because a wrong ABI does not show at install
time but at the first `System.loadLibrary`.

### Expansion files, and the permission that buys nothing

The plan said "OBB files too, therefore `MANAGE_EXTERNAL_STORAGE` with dedicated onboarding". **The
permission is not declared, and the reason is a measurement**: it achieves nothing.

Measured on Android 16 (API 36) with the permission **granted** and
`Environment.isExternalStorageManager()` returning `true`:

| path | outcome |
|---|---|
| `Android/obb/<other package>/` | `mkdirs` **false**, open `ENOENT` |
| `Android/data/<other package>/` | `mkdirs` false, `ENOENT` |
| `Android/media/<other package>/` | `mkdirs` false, write `EPERM` |
| `Documents/…` | **succeeds** — that is, the probe was measuring something |

This is the documented restriction on `Android/data`, `Android/obb` and `Android/sandbox`, which that
permission does not override. The only identity that can write there carries the supplementary group
`ext_obb_rw`, and on a device that is one thing: **the shell**, that is Shizuku or root.

**The first measurement said the opposite, and it is the most valuable exhibit here.** On a fresh
install, with the permission declared and **never granted**, `mkdirs` succeeded. Re-measured after a
device reboot, every combination fails — including the one with the permission granted. A uid's mount
mode is decided by the system when the process forks; the first measurement was taken on a namespace not
yet restricted.

Three consequences:

1. **the permission is not requested** — it would buy nothing, and it is Android's most invasive one;
2. **where there is no privileged shell, a container with game data is refused**, and refused **before
   extracting**: `ContainerProblem.ExpansionsNeedPrivilegedInstaller`. Installing anyway would leave a
   game that starts and behaves as if its data were missing;
3. **there is no "download it and move it yourself" fallback.** The user's file manager trips over the
   same restriction: handing it an OBB in `Download/` would be handing over a file it cannot put
   anywhere.

Where the shell exists, the bytes go through `cat > /sdcard/Android/obb/<package>/<name>` on stdin, for
the same reason the APK goes through `pm install-write … -`. **The file name is not touched** — Android
looks for `main.<versionCode>.<package>.obb` by name — while **the folder does not come from the
container**: it is decided by the `packageName` verification read from the base APK, because an
`install_path` obeyed literally would be a path written by the store.

---

## Which version to offer

Measured against the F-Droid index: **14 packages out of 4,257** have a raw maximum different from the
correct version. The rule, in `:core:common`, `VersionSelection`:

1. installable artifacts only (on F-Droid, filter the three `.zip` entries). "Installable" includes
   split containers;
2. the **default release channel** only, unless the user explicitly asks otherwise — 28 versions are in
   `Beta`, including the highest one of `org.fdroid.fdroid`;
3. **compatible** versions only: `minSdk ≤ SDK_INT`, and a `nativecode` set intersecting the device's
   ABIs (or absent = universal);
4. **signer**: if the package is installed, the installed signer wins; otherwise the one the store
   recommends. 15 packages have more than one signer, and one publishes **the same versionCode twice**
   with different keys;
5. among the survivors, the highest `versionCode`; on a tie, the build whose ABI is closest to the
   device's preferences.

If no candidate remains **only** because of the signature, the outcome is not "nothing to do" but a
declared conflict: the offer becomes "uninstall and reinstall (you lose the data)".

`allow_preview_channels` is read in exactly one place — `selectVersion`, the only place in `:core:data`
that builds a `VersionSelection.Request`. From there it applies to the app page and the periodic check
together. If only one of them read it, the page would offer the update the worker refuses.

### "Up to date" and "cannot be known" are two sentences

`VersionSelection.Outcome.UpToDate` carries a `comparable` field. It is `false` when the store does not
publish a `versionCode` and the comparison could not be made — **uptodown publishes it nowhere on the
site**, so without this distinction every app taken from there would say "up to date" forever, with the
same confident face as one that really is.

It is a field and not a variant because it leads to no different action: it changes the sentence, not
what can be done. By the same criterion `OnlyOtherChannels` *is* a variant — there the two situations
lead the user to two different gestures.

### `ignore_updates` and `pinned_version_code`: two things, in two places

|  | What it changes | Who reads it | What the app page shows |
|---|---|---|---|
| `ignore_updates` | whether to disturb the user | `UpdateRepository` | nothing: the "Update" button stays |
| `pinned_version_code` | **which version is offered** | `VersionSelection` | the outcome becomes `Pinned` |

Two consequences not to invert: **the pin lives in `VersionSelection`**, otherwise the page would offer
the update the periodic check refuses — two different answers to the same question. **The pause does
not belong there**, because it does not change which version is best. It is read in two places for two
effects: it removes the app from the update list, **and it spares it the network request**. The second
concerns the store on the other end, and it is the one people forget.

The pin speaks **only when it is holding something back**, and it is applied by comparing two answers
rather than filtering at the input. Filtering first, a beta newer than the pin would produce a "pinned"
outcome even though the release channel would have discarded it anyway — and the user would read that
their pin is withholding an update they were never going to get. The pin means "no further", not
"nothing": whoever pins at 100 having installed 90 wants to reach 100.

### Version history

`StoreCapabilities.versionHistory` was declared `true` by eight adapters out of nine, all eight
implemented `getVersions`, the contract test verified the two matched — and for a long time **no
production line called that method**. What the method costs varies enormously:

| store | what it does |
|---|---|
| **apkcombo** | `/old-versions`, **a page of its own** |
| **apkmody** | `/history`, **a page of its own** |
| **modyolo** | the first download variant's page: **one extra request** |
| apkmirror · uptodown · pdalife · liteapks | the **same** versions as the listing — an alias of `getAppDetails` |
| f-droid | `Unsupported`: the index already carries them all |
| an1 | does not declare the capability — one listing, one file |

Hence the shape: **collapsed by default, and the request starts on expansion**. On three stores out of
nine, opening it is a fetch to a third-party site, and doing that on every page opened would be
speculative prefetching. On four it costs nothing; on two no request goes out at all.

On apkcombo the history does not add rows — it makes a listing installable that was not. Measured on a
device: the page said "this store publishes no installable package for this app" — zero artifacts — and
expanding the section produced the Install button, the current version and nine previous ones, because
on that store the files live **only** on `/old-versions`.

`AppDetailRepository.loadVersionHistory` returns early, with `Success`, when the capability is off and
when the store is index-backed: in neither case has anything failed. It returns `Failure` on an open
circuit, and **declares the real failure** — answering `Success` to a page that did not answer would
leave the listing's single version on screen **as if it were the whole truth**.

`CatalogDao.saveListing` begins with `clearVersions`, because it is rewriting what the listing declares
*now*. `mergeVersions` does the opposite: it adds, and leaves the conflict to the unique index. The two
coexist on one condition: **whoever loads the history waits for the listing refresh** (`refreshJob?.join()`).
Without that, a history merged while a refresh is in flight would be deleted an instant later. That
window is real, not theoretical — the section can be expanded as soon as the page appears, which is
exactly when the cache is there and the refresh is not.

`VersionSelection.installability` judges **one** version where `select` chooses among all:
`INSTALLABLE`, `INSTALLED`, `OLDER_THAN_INSTALLED`, `INCOMPATIBLE`, `UNSUPPORTED_ARTIFACT`. The third is
why the function exists: **Android does not replace an app with an earlier version, and neither installer
asks it to**. Offering that row would mean a whole download and a refusal from the system at the end.
Two things the verdict deliberately does **not** look at: the signature (that is step 5 of the pipeline,
which already has the sentence and the gesture) and an absent `versionCode` (four stores out of nine do
not publish one; without a number the comparison is not made and the version stays pressable — the same
honesty as `UpToDate.comparable`).

---

## Updates

An app installed from a store is updated **from that same store**
(`installed_apps.update_channel_listing_id`), not from the first store with a higher `versionCode`:
changing signer mid-life makes the update fail at the OS level. The user can change channel explicitly,
and is warned about the possible signature conflict.

**The channel is a column, not a synonym for the origin.** The domain model used to copy
`updateChannelRef` from `source_ref`: identical until somebody changes channel, different the instant
they do — that is, a changed channel would never have been used. `InstalledApp` now carries
`updateChannelListingId`, `updateChannelStoreId` and `updateChannelRef`, read from the `LEFT JOIN` on
`store_listings`, and they stay `null` when the channel points at a listing a sync deleted. **No foreign
key prevents that dangling pointer, and there must not be one**: a package withdrawn from a store is not
a reason to forget the user has it installed.

### The periodic check

Two things the worker does **not** do, each for its own reason:

- **it does not retry.** `Result.retry()` would trigger WorkManager's backoff, that is knock again at a
  door that just said no. The next period comes anyway, and what the other stores answered is already in
  the catalogue;
- **it does not query the whole catalogue.** Only the channels of installed apps, and not even those of
  paused ones. On an index-backed store that is **one** sync, not one request per app.

**It downloads and installs only if asked to.** With the switches off — that is, for whoever never opens
Settings — it stops at an updated catalogue and a notice. `auto_download_updates` makes it fetch the APK,
`auto_install_updates` also install it. They are two and not one because the second has a prerequisite
the first does not — an installer that asks nothing — and tying them together would mean that on an
ordinary device not even the first could be switched on.

Three consequences of nobody watching, none of them optional:

- the transfer **waits** for an unmetered network, unless the user allowed otherwise. Whoever just
  pressed a button has already decided to spend that traffic; whoever is asleep has not;
- the installation, if it happens, must be silent (`InstallPlan.requireSilent`);
- **the list is re-read after applying**, not before. What was just installed is no longer an available
  update: announcing first would mean a notification listing precisely the apps just updated.

**The worker waits for the downloads instead of enqueuing and exiting**, and that costs: WorkManager
stops a worker after ten minutes. The choice stands anyway, because enqueuing and exiting would make
installing impossible — between the end of the download and the installation there would be nobody left.
And the case where the ten minutes run out is not a fault: the transfer lives in its own worker with its
own foreground service and carries on, the file stays in staging, and at the next period `enqueue`
returns that same row and `start` finds it complete.

**The notification is not only rewritten by the worker.** Between one run and the next the list can
shrink — the user installs an update from the Home, or updates an app elsewhere — and the notice would be
left listing apps with nothing to update. The right signal already exists and is precise: **a package
changed**. `UpdateNotice.refresh()` is hooked to `PackageEvents`, the same broadcast that realigns "My
apps".

Scheduling **observes** the settings rather than being done once at startup: without that, changing the
interval would have no effect until the app restarts, that is three Settings entries that appear to do
nothing. `ExistingPeriodicWorkPolicy.UPDATE` and not `KEEP` for the same reason, and not `REPLACE`
because that would reset the period already under way.

### The four notifications

The periodic check can say four things, on **three channels**, each with its own switch. The criterion
that holds them together: **every notice reports an event that arose on its own.** What the user just
asked for is reported by the screen in front of them, in more detail than fits in a notification.

| notice | when | channel |
|---|---|---|
| "N updates available" | the check finds something | `updates` |
| "N apps ready to install" | the file arrived and a tap is missing | `installs` |
| "N apps updated" / "N could not be updated" | outcome of an **unattended** installation | `installs` |
| "N stores did not answer" | the periodic check could not reach a store | `stores` |

Three consequences that are not details: **each one's scope has to be in the description**, because the
name is not enough — "tell me when a download finishes" makes people expect a notification for the file
they just asked for, and its absence reads as a broken switch. **The outcome's title reports the worst
case**: "3 apps updated" above a failure buried in the body is how you make sure nobody reads it. And
**"a store is not answering" is not the same news as a store failing during search** — there the notice
next to the results already says it; here the set of stores queried **is** the set of update channels of
installed apps, so a silent store means apps that stop updating, and nothing else would say so.

### The installed row says what it was, not what it is — until reconciled

`installed_apps` is written by us at install time. A package that changes **without going through us** —
another store, a sideload, `adb install` — does not touch it, and the row goes on announcing a version
that is no longer on the phone.

The failure is silent and credible, which is why it is named here: the **decisions** already read the
`PackageManager`, so the answer is right while the sentence under the user's eyes is wrong. Measured on
an emulator: an app rolled back with `adb`, and "My apps" kept saying "Version 1.3" above the row
"update available: 1.3".

`reconcile()` therefore does two things: it removes what is no longer there **and** rewrites the name,
version and signer of what changed. It does not touch origin or channel — those are ours, and what
changed is the package, not where it will be updated from. It runs at startup and on every
`PackageEvents`, and only writes when something actually changed: the table feeds a `Flow`, and
rewriting identical rows would recompose "My apps" on every launch.

### The packageName we know ourselves

Four stores out of nine do not publish the `packageName`. Starting from their listing there is no way to
know which package to query, and the page would say "Install" to somebody who already has the app —
forever, because nothing could change its mind. But we do know the name: **the APK told us when we
installed it**, and it is in `installed_apps`. Hence
`InstalledAppsRepository.forListing(storeId, ref)`, the inverse path of `get`. It looks at both the
origin and the channel, because after a channel change the listing the user is on is the second one.

### Self-update

MultiStore is not on any store, so its own updates come through `index.json` — the same request the Home
already makes, with one extra field.

- **the verification is the same.** The APK goes through the seven steps, including the signer comparison
  against the **installed** one read from the `PackageManager`. That is the check that makes a
  correctly-signed index pointing at somebody else's package harmless: the two signatures protect
  different things, and the second is not replaceable by the first;
- **a hash mismatch is not negotiable**, and does not consult `allow_unverified_hash`: that field exists
  because some of the nine stores publish stale hashes, and this index is published by us. The
  non-matching file **is deleted** rather than left in staging;
- **no silent installation.** Updating yourself kills the process halfway through the commit: doing that
  without the user having just pressed something means an app vanishing from under their fingers. It is
  the same reason "update all" puts MultiStore last.

`InstallPlan.storeId` and `ref` are nullable for this. With no store, no `installed_apps` row is
written — and that is not a simplification: that table says "MultiStore installed this app from this
store, and will update it from there". Writing it would give a channel pointing at a listing that does
not exist. Verified on a device after a successful self-update: `installed_apps` has zero rows.

The download deliberately does **not** go through `:core:download`. That engine exists for gigabyte files
from hosts we do not control that must survive the process dying: a Room row, `Range` resumption tied to
an `ETag`, a foreground service, a worker — every piece indexed on `(storeId, ref, versionRef)`, and
MultiStore has none of the three.

---

## Cross-store identity

Many stores do not publish the `packageName`, so "the same app" often has to be **inferred**.
`IdentityMatcher`, in `:core:common`, is the only place that inference happens, and it is pure Kotlin.

Three rules that do not move:

1. **different `packageName`s are a veto**, not a low score. No title, no icon, no developer can overturn
   that. The case is real and measured: one store redistributes Telegram as
   `org.telegram.messenger.web`, another as `org.telegram.messenger`. Everything else matches; the
   package does not, and the package is right.
2. **below `0.85` nothing is ever merged silently.** The listing goes into a "possible match" section
   where the user confirms or rejects, and the choice is written to `identity_overrides`. A wrong merge
   must be made *impossible by construction*, not improbable.
3. **sharing an `app_key` is not enough.** `AppKeys.inferred` derives the key from normalised title and
   developer, so two listings with neither packageName nor declared publisher share it while still
   scoring 0.80 for the matcher. Whoever reads a listing's "siblings" has to look at
   `store_listings.match_confidence`.

**No prefetching to fill the section.** Opening a page does not query the other stores. The sources are
three, in order of cost: `store_listings` (free), what the last search already saw (free, and it covers
the normal path), and the **"Search the other stores"** button, which is a user request.

### A domain key is not a list key

`AggregatedApp.appKey` is the identity several stores use for the same app: `pkg:{package}` where the
store publishes it, `sig:{digest of title + developer}` where it does not. The second form **is not
unique by construction**. While the stores without a package were few, the collision did not happen. With
an1 — which publishes the packageName on no page — it happens on the first search, and it showed up in
the worst way: **`IllegalArgumentException: Key "sig:…" was already used`**, that is `LazyColumn` closing
the app.

The fix is **not** to make `appKey` unique: that key has to keep matching the row already written in
`store_listings` for the same package. It is to separate the two questions —
`AggregatedApp.listKey` appends the listing that represents the group, which is unique by construction and
stable across recompositions.

**General rule: before using a domain identifier as a list key, verify it is unique *in that list*.**
Those are two different properties, and only one of them is guaranteed by whoever wrote the key.

---

## Search filters

The plan listed seven filters — category, store, size, rating, app/game, min SDK, ABI — assuming the
stores could filter. A census run by putting the nine adapters over the **committed search fixtures**
shows how broad that assumption was:

| store | rows | `contentKind` | `rating` | `categories` | `lastUpdated` | `versionCode` |
|---|---|---|---|---|---|---|
| f-droid *(index)* | 11 | **11** | 0 | **11** | **11** | **11** |
| f-droid *(fallback)* | 10 | 0 | 0 | 0 | 0 | 0 |
| apkcombo | 20 | 0 | 19 | **20** | 0 | 0 |
| apkmirror | 10 | 0 | 0 | 0 | 9 | 0 |
| apkmody | 20 | **20** | 0 | 0 | 0 | 0 |
| modyolo | 20 | 0 | 0 | 0 | 0 | 0 |
| an1 | 10 | 0 | **10** | 0 | 0 | 0 |
| pdalife | 18 | 0 | **18** | **18** | 0 | 0 |
| uptodown | 36 | 0 | 0 | 0 | 0 | 0 |
| liteapks | 7 | 0 | **7** | 0 | 0 | 0 |

Three consequences, none of them opinions:

- **size, min SDK and ABI do not exist in `StoreListingSummary`**, and none of the nine search parsers
  populate them. They live in `AppVersion`, that is on the detail page, which a search does not download:
  filtering on them would mean one request per row. They are not search filters;
- **apkcombo publishes the rating on 19 rows out of 20**, and that number is what turns "always present"
  into a rule rather than a phrase. "Nearly always" is not enough: filtering on a field that sometimes
  goes missing means discarding rows nothing is known about and presenting the result as if the filter had
  judged them;
- **categories exist on three sources and the three vocabularies do not intersect.** F-Droid publishes
  "App Store & Updater", "Keyboard & IME", "Pass Wallet"; apkcombo the Play categories; pdalife game genres.
  A single list would be one where every entry means something different depending on who answers. Category
  stays where it is coherent: browsing **one** store's catalogue.

### Three tiers, and the third declares itself

`FilterPlan`, in `:store:api`, decides per active filter and per store:

| tier | when | what happens |
|---|---|---|
| `STORE_SIDE` | the filter is in `supportedFilters` | it is passed along: the store applies it, over the whole set and **before** pagination |
| `CLIENT_SIDE` | the filter is in `clientFilters` | query and discard here: the field is on every row |
| `UNSUPPORTED` | neither | **the store is not queried at all**, and the screen says so |

The third tier is why the mechanism exists. Letting through the whole results of somebody who cannot
filter produces a list containing exactly what the filter claims to have excluded, with no row telling
them apart; not querying them costs one fewer request to a third-party site and gives a fact to show. That
notice is **its own**, separate from the failures one, because the remedy differs: there you retry, here
you remove a filter — and a "Search again" button above this list would knock at the same door for the
same silence.

**Two filters do not go through it**, and they are the two one would govern first. `includeNsfw` has its
active value coinciding with the default: treating it like the others would mean every ordinary search
querying only the single store that labels adult content. And **sorting** excludes nothing.

`clientFilters` sits in `StoreCapabilities` next to `supportedFilters` and says something different: not
"the store can filter" but "the field is on **every** row this adapter produces". The contract test
censuses the real fixtures and requires **equivalence**, because the two errors differ and neither would
be visible by eye: declaring it with a row that lacks the field is a filter discarding what it did not
judge; *not* declaring it when the field is always there is a store excluded from a search it could have
answered.

**An index-backed store on its fallback is not a store that can filter.** F-Droid declares seven
`FilterCapability` values and **the index** applies them, with a SQL query. Until the index has been
downloaded, the fallback search answers — the remote ten-result API, which accepts no filter — so in that
window the same store sits in the **third** tier, not the first.

### Sorting applies to the aggregate, and only to what is visible

A per-store ordering, merged, is not an ordering: the nine lists would each arrive sorted on their own and
the result would depend on who answered first. So the aggregated list is sorted, and **the criterion is
always a field of `displaySummary`** — the number the user sees. Sorting by something other than what is
shown gives a list that looks wrong precisely to whoever reads it carefully, and it is why the search row
shows the rating.

Three criteria are offered out of six, and the difference is the table above: the title is published by
nine stores out of nine, the rating by three, the date by **none** (apkmirror carries it on 9 rows out of
10), the download count by none — and apkcombo's is a label (`10M+`), not a number.

**Whoever has no rating goes last, not to zero.** `null` means "this store does not publish ratings", and
treating it as "zero stars" would say something about those apps that nobody said.

### Adult content: the label is filtered, not the content

`show_nsfw_content` (off by default) excludes from results what a store **declares** adult. Three things
not to confuse:

1. **only one store declares it.** modyolo publishes six WordPress categories and accepts
   `categories_exclude`, so the filter is **server-side**. For the others the setting has nothing to act
   on, and the `FilterCapability.NSFW_CONTENT` capability says exactly that.
2. **the label is incomplete, and the user has to be told.** The three most recent articles on modyolo
   were adult visual novels, all three filed under "Role Playing". The setting's description says "hides
   what the store declares", not "hides adult content", and the difference is not legal caution: it is the
   only true sentence.
3. **it is not guessed from keywords.** Filtering by string would hide "Truple Porn Filter Blocker", which
   is a parental control, and would still let through whatever does not name itself. If a source does not
   label, the honest answer is not to declare the capability.

It is applied in `SearchRepositoryImpl`, before the fan-out — not in the caller. Passing it in
`SearchFilters` from the ViewModel would have worked until somebody wrote a second screen that searches,
and forgetting produces no error: only content the user asked not to see. The caller's value **cannot
override** the setting, and that is verified by removing the application and watching two tests go red.

### What the app chooses on its own is held to a stricter rule

The Home's two sections come from a signed `index.json` produced by `:tools:index`. Which sources may feed
them was decided by measurement.

modyolo's feed had 24 entries of which **six were adult content, and all six filed outside the six
categories that store declares adult**. `show_nsfw_content` filters the label; there the label is not
there. The setting would remove **nothing**, and a quarter of that store's most recent catalogue would
land on the Home of somebody opening the app for the first time without having asked for anything.

Hence: **modyolo does not feed "new releases"**, and the exclusion is a `Set` in `BuildIndex` with the
measurement beside it. It is not a judgement on that store — its search stays, and a search is something
the user typed. It is the difference between what the app *shows* and what the user *asks for*.

The other five surfaces were each inspected and are clean. The same inspection found the case that shows
why the container matters: a strip at the bottom of one ranking page contains unlabelled adult titles. It
is not the one we read — the parser is anchored to a specific container, not to `.item`.

---

## Settings

**The permission to install has a Settings entry**, in the Installation section, and it is not a setting:
it is an **action**, like exporting diagnostics. Android remembers that value, and a field in
`settings.proto` would be a second copy of something we can only read — the same trap that keeps store
enablement in Room. It exists because it used to be reachable **only** from the notice on an app page
whose installation had already failed: whoever denied it by mistake had to break something in order to
find the way to fix it.

**Store enablement lives in Room**, the `enabled` column of the `stores` table, because that is what
`SearchRepository` reads. A second copy in the DataStore would be a value that diverges.

Two reserved field numbers are deliberately **not** written:

- `remote_index_url` / `parsers_url`: the address is a fact of the build (`BuildConfig.*_URL_OVERRIDE`,
  empty in every normal build) and the signature stays pinned, so a different address can at most deliver
  no document. A text field in Settings would buy the ability to type a wrong URL and nothing else;
- `search_store_ids`: which stores to query is already known, from one place. The "for this search only"
  choice exists, is in the filter panel, and is **transient state**;
- `http_cache_max_mb`: measured on the device after a day's use with nine stores wired, the HTTP cache
  occupied **1.5 MB** against a 50 MB cap. The maximum size is also chosen when `okhttp3.Cache` is
  constructed, so the entry would have to say "applies from the next launch" — a switch that asks for a
  restart to change a cap that is never approached. What is needed at that level is the button that empties
  it, and that exists.

---

## Remote configuration

`index.json` and `parsers.json` are Ed25519-signed with a pinned public key. A configuration with an
absent or invalid signature is **discarded**, and the compiled defaults are used.

**The compiled defaults must always exist and stay current.** Remote configuration is an override, never
the only source: a CDN outage must not make the app useless.

### What is signed: the bytes, not the object

The envelope is `{"algorithm":"ed25519","payload":"<base64>","signature":"<base64>"}` and the payload
travels base64-encoded. Signing a JSON structure would oblige the verifier to re-serialise it identically —
same key order, same spacing, same number formatting — and any discrepancy would make a valid signature
come out invalid. This way the bytes verified and the bytes interpreted are **the same bytes**, by
construction: the same closure with which `SessionInstaller` computes the SHA-256 *while* writing into the
session.

Verification uses **BouncyCastle**, not the JCA: Ed25519 enters the system provider at API 33 and `minSdk`
is 26 — below that, `Signature.getInstance("Ed25519")` would throw. The public key is in `ParsersKey`; the
private one is **not in the repository** (`.gitignore` excludes `.secrets/`).

### The override is partial, and says what it did not apply

`RemoteParsers.override(storeId, default, serializer)` is the single point where remote configuration
touches anything, and `:app` interposes it between a `<Store>Config` constructor and the adapter that
receives it. It goes through the serialised representation rather than having a method per store, so
**every field of every configuration is overridable by construction**, including ones that do not exist
yet.

Four rules, all aimed at not letting an error pass silently:

1. **`encodeDefaults = true` is the linchpin.** A compiled configuration is made *only* of defaults, and
   kotlinx omits them by default: without that flag the starting object would be `{}`, every override key
   would come out unknown, and remote configuration would accept the document **without applying anything**.
2. **The `KSerializer` is passed explicitly.** A convenient `inline fun <reified T>` compiles and fails at
   runtime: inside an inline function body `T` is a type parameter, so the plugin does not rewrite
   `serializer<T>()` and the reflective variant remains. That is how it was discovered that one store's
   config **was not `@Serializable`** — invisible because nobody had ever serialised it. With an explicit
   serializer the same omission does not compile.
3. **A key the default does not have is discarded and counted**, with its path, and the Settings screen
   lists it. A typo would otherwise give a valid signature, an accepted document and no effect — the worst
   possible diagnosis.
4. **A value of the wrong type costs that store, not the document.** A typo on one store must not revert
   another to how it was before the fix being published for it.

### An accepted document applies from the next launch

The configuration reaches the adapters through the constructor, once per process. Making it observable
would mean letting an adapter change selectors halfway through a search, with the page downloaded under one
configuration and interpreted under another. The Settings screen says so in those words, and distinguishes
**three** things that break separately: what is in use now, how the last attempt went, and what was not
applied.

The document is downloaded at startup if the cached copy is older than **six hours**, and never with
`block_remote_parsers` on — not even by pressing "Check now", which would be a way of bypassing the setting
with a button.

**`index.json` behaves the opposite way, and the asymmetry is deliberate**: it is *content*, nobody
interprets it, and a Home showing the old list after downloading the new one is not prudent, it is broken.
Verified on a device: the app was already open when the index arrived, and the two sections appeared without
a restart.

### The Home from the signed index

Three things are not negotiable and one is a measurement.

**The document may be missing, and that is not an error.** Without an index the two sections simply do not
appear and the Home stays the local-catalogue one. A CDN outage does not produce an empty screen.

**Entries from a disabled store do not appear.** The filter reads `stores.enabled` in Room, the same column
`SearchRepository` uses: without it the Home would offer an app from a disabled store, and tapping it would
open a page search would never show.

**RRF exists, and it is measured how little it fuses.** The plan asked for Reciprocal Rank Fusion with a
sound justification: absolute download counts are not comparable between stores, rankings are. The formula
is there. What it fuses, measured twice: with a **permissive** title comparison, three apps out of 27 appear
in more than one list; with **the identity the app actually uses** — normalised title and developer —
**none**. `Spotify` and `Spotify Pro Mod APK` are two different keys, and no ranking publishes the
packageName that would correct the inference.

The produced document indeed has 22 entries with `sources: 1` throughout. **The comparison is not widened to
make it look better**: a permissive criterion in the pipeline would publish as "one app" two listings the app
itself would show separately — and publish it *signed*. Today RRF is therefore a **rank-weighted
interleaving**, which is the right ordering anyway, and it will start fusing the day a ranking publishes the
package.

### Testing the channel without touching the constant

`-Pmultistore.parsersUrl=http://10.0.2.2:8000/v1/parsers.json` replaces the pinned address in that build
(`BuildConfig.PARSERS_URL_OVERRIDE`, empty in every normal build). The `debug` variant — **only** it — allows
cleartext traffic to the emulator host. Not a hole: the signature stays pinned, so a different address can at
most deliver no configuration, never one we did not sign.

**And the proof is not that it works: it is that it refuses.** With a document signed by the real key, the
minified build downloads, verifies with BouncyCastle, deserialises with kotlinx and draws the two Home
sections. With **the same payload signed by a different key**, it applies nothing. Without the second
experiment the first would only say "it read something".

---

## Caching

Four levels with separate responsibilities — do not mix them:

| Level | What | Policy | Measured on a device |
|---|---|---|---|
| Room | application truth: index, listings, versions | `ttl_seconds` **per row**, set by the adapter | **62.3 MB** |
| staging | downloaded, not yet installed APKs, **and an opened container's pieces** | discarded on successful installation, unless the keep-APK setting is on; the extracted pieces always, being derived data | **28.2 MB** |
| Coil (200 MB) | icons and screenshots | disk LRU, configurable cap | 4.3 MB |
| OkHttp Cache (50 MB) | raw HTTP responses | the store's headers, overridden **only where there are none** | 1.5 MB |

Reads are always **stale-while-revalidate**: show the cached value immediately (marked if expired), refresh in
the background, Room re-emits through a `Flow`.

### The automatic purge, and what it deliberately does not cover

With nine stores wired, the measurement is clear:

```
store_index_entries   37,384,192   57%    4,269 rows, all f-droid
store_listings        12,881,920   19%    4,268 of 4,279 rows are f-droid
app_versions           6,352,896    9%
listing_screenshots    3,403,776    5%
expired rows:         0 out of 4,279
```

The two lines that matter are the last and the second. **No row had expired**, and not because the cache was
fresh: because what fills the database is a signed index with a seven-day TTL, resynced whole. A TTL-based
purge would have reclaimed the eleven rows of the other eight stores — a few kilobytes — without ever touching
the 95%.

Hence the shape of the policy, and **the 95% is deliberately outside it**: an index-backed store's catalogue is
not cache that ages, it is a catalogue the user asked to download. Throwing it away on our own would mean a
search that stops finding things with nobody having asked for anything. It is discarded by a button that says
what re-fetching costs.

**Five protections, and four of them concern rows that are not cache at all.** The query is a single one —
`CatalogDao.deletePurgeableListings` — used by both the automatic purge and the button with different
parameters: two copies of the same clause are two copies that can diverge, and a button that freed space by
detaching every installed app's update channel would be worse than the problem. A listing is never touched if
it belongs to an index-backed store; is an installed app's update channel; is an installed app's origin (which
`installed_apps` remembers as a **second** key); is a download's listing; or is the object of a match the user
confirmed in `identity_overrides` — the last being the only one that defends a **decision**, and throwing it
away would ask the user the same question again.

**The automatic purge does not compact, the button does**, and those are opposite costs. `VACUUM` rewrites the
whole database: doing it at every launch to reclaim the pages of eleven rows would mean rewriting 62 MB to free
a few tens of kilobytes, in a file the next sync refills — free pages *inside* the file are exactly what it
needs. The button exists because somebody asked for **space back**: without `VACUUM` it would delete 60 MB and
leave the file as large as it was, with the screen reporting the same number as before. That is not
imprecision, it is a button claiming to have done something it did not.

**Emptying the catalogue is four things, and the fourth is the one people forget:** listings, apps left with no
listings, index entries, and the index **state**. Without the fourth, the next sync would find a valid token and
ask for a **diff** against a document that no longer exists: it would apply the differences to nothing and
declare itself up to date, leaving an incomplete catalogue forever with no error anywhere. Verified on a device:
after emptying, the Home says "the catalogue is not on this device yet" — the app *knows* it does not have it —
and the next tap re-downloads it in full.

### `VACUUM` in WAL mode: the checkpoint comes after

The database runs with `WRITE_AHEAD_LOGGING`, and in WAL mode **`VACUUM` rewrites the entire database inside the
write-ahead log**. Doing `wal_checkpoint(TRUNCATE)` *before* the `VACUUM` therefore leaves a `-wal` as large as
the database: measured on a device, 65.3 MB became 129.4. The order is `VACUUM` → checkpoint.

The defect is insidious because it presents as its own opposite: the saving comes out negative, is clamped to
zero, and the operation announces "nothing to reclaim" having just doubled the footprint. **A test that looks
only at the total does not see it** on a small database: the `-wal` has to be measured on its own.

### An APK nobody can delete, because whoever could has just died

The case that made a startup sweep necessary, and no test would have found it: `InstallSelfUpdateUseCase` writes
MultiStore's own APK into `files/staging`, and after the commit **the process is killed by the system** — there is
nobody, ever, who can delete it. Measured: **28.2 MB** sitting there for a day, in a private folder no file
manager can open.

The sweep looks at files *and folders*, and the second half is not theoretical: opening a container leaves a
folder in staging with base and splits inside, and an `isFile` filter would ignore it forever. A folder is
protected when the download it came from is, and the correspondence between the two is known by `Staging` and by
nobody else.

The automatic sweep therefore discards what **no `downloads` row claims**; the button is wider and also discards
the files of already-finished rows, sparing only transfers in progress or paused — their partial file is what
resumption needs. Finished rows disappear **after** their files: a row with no file would offer "Install" on
nothing.

### The `ImageLoader`, and what the measurement says is *not* the reason

`coil-network-okhttp` had been on the classpath from the start and nobody configured an `ImageLoader`. The
conclusion drawn was "icons do not go through our OkHttp, so they have neither the store's UA nor our cache". The
first half is true; the implicit conclusion — that the UA is the problem — is not. Measured **with OkHttp**, over
the six hosts the nine stores' icons come from: **200 with no User-Agent at all, on all six**, with responses
byte-identical to those obtained with a Chrome mobile UA. The UA is set anyway — it is standard practice.

The two real reasons are elsewhere:

- **a size nobody had chosen.** Coil's default is **2% of the free space**: on an emulator with 3,184,072 KB
  free, about 62 MB; on a phone with 200 GB free, four gigabytes. It is now ~200 MB — and configurable;
- **a second connection pool** towards hosts that already had one. `f-droid.org` serves both the pages and the
  icons, so the two-requests-per-host cap applied out of courtesy was the cap of *half* the traffic.

The image client therefore derives from the base one — same pool, same cookie jar — with three deliberate
differences: **no HTTP cache** (Coil has its own, and keeping both would store the same bytes twice, evicting
store pages to make room for icons already saved elsewhere); **its own dispatcher** with a higher per-host cap (a
list of icons is not scraping, it is what a browser does with a page's subresources, and on the shared dispatcher
the icons would queue *behind* a request already waiting three seconds of `Crawl-delay`); and **no rate
limiter** — which needs no removing, because it lives in `StoreHttpClient`, but is a choice rather than an
oversight.

**Decimal megabytes, not binary**, and the device decided that: the screen writes every size with
`Formatter.formatShortFileSize`, which on Android uses SI units. With 1024² a "200 MB" cap would have appeared as
**210 MB** next to a list of choices reading 67, 210, 537 and 1.07 GB, and it would have been the only place in
the app where a megabyte means something different from what the system shows.

### The cache override fills a silence, it does not contradict an answer

Measured over the five stores' search pages with a Chrome mobile UA:

| Store | What it sends | Effect on OkHttp |
|---|---|---|
| f-droid | `ETag` + `Last-Modified` | heuristic and revalidation: works |
| apkmody | `public, max-age=7200` | two hours of caching, already right |
| **apkmirror** | **nothing**: no `Cache-Control`, `Expires`, `ETag` or `Last-Modified` | **nothing cached, ever** |
| apkcombo | `no-store, no-cache, must-revalidate, max-age=0` | nothing cached, **at the site's request** |
| uptodown | `private, no-store, no-cache` | nothing cached, **at the site's request** |

The last three rows only resemble each other in their effect. apkmirror **said nothing**; the other two said "do
not store". `CacheHeaderInterceptor` acts **only on the first case**, and `StoreNetworkProfile.pageCacheTtl` stays
`ZERO` — that is, "respect the headers" — everywhere else. The criterion is the same one that governs everything
here: behave like a browser, and a browser that ignores `no-store` is not aggressive, it is broken.

---

## Diagnostics

The promise from the first draft was "**local and user-exportable** diagnostics". The first half was true:
`health_events` has recorded what goes wrong from the start. The second was not — the table filled up and nobody
could read it, which makes the sentence true only in the part that does not matter.

**`diagnostics_log_enabled` switches on what the log did not record: successful requests.** The reason it did not
is written in `StoreHttpClient` and is correct — one row per ordinary request would fill the table with the news
that nothing happened. The defect only shows when you try to *use* the diagnostics: the two most common questions
— "why is search slow?", "why does this store find nothing?" — both have the shape where the failure log is
**empty**, and empty precisely because nothing failed.

Four things not to change:

- **a 403 is recorded too.** "It arrived and said no" and "it did not leave" are two different diagnoses, and
  telling them apart is half of why the log exists;
- **the stopwatch starts after the rate limiter.** apkmirror declares `Crawl-delay: 3` and we wait those seconds
  on purpose: counting them as response time would turn our own good manners into a diagnosis of somebody else's
  slowness. The `TimeSource` is injected for the same reason `Clock` and the dispatchers are — without it the
  measurement is not testable: under `runTest`'s virtual time, `TimeSource.Monotonic` answers "zero milliseconds"
  to anything and the test stays green even while counting the wait;
- **the switch is re-read on every request**, not captured when the graph is built. Capturing it at startup would
  cost nothing while off and make switching it on have no effect until restart;
- **the description says the addresses contain the search terms.** On one store the query is in the path.
  Removing them would make the log useless for the very question that gets it switched on; not saying so would be
  worse, because a report gets sent to somebody.

**The report is in English, and that is not an exception to rule 1.** That rule concerns strings visible in the
interface, and the export's interface — label, description, outcome — is translated like everything else. The
file's content is a technical artifact to paste into a bug report, and its keys are field names (`versionCode`,
`minSdk`, `resolverTier`). Translating them would make comparing two reports five times harder.

**The first defect was found by the report reading itself.** On a device with 4,269 apps in the catalogue,
F-Droid showed `lastSuccess=never`: the index sync did not go through `recordSuccess`, and for an index-backed
store that **is** the only request made. The failure, though, stays a diagnostic event and does **not** feed the
circuit breaker, because the breaker would govern something other than what broke — the fallback search, which on
F-Droid talks to a separate host.

---

## Testing

### The nine guardrails

They are the executable form of the three rules. They all run offline in seconds.

| Guardrail | What it prevents | Command |
|---|---|---|
| `MultiStoreComposeHardcodedText` + `HardcodedText` | a string written in the code instead of `strings.xml` | `./gradlew lint` |
| `TranslationParityTest` | a key present in one language and absent in another, or orphaned | `./gradlew :guardrails:test` |
| `SettingsCoverageTest` | a `settings.proto` field with no Settings entry, and vice versa | `./gradlew :guardrails:test` |
| `SettingsScreenCoverageTest` | a registry entry **no row draws** — the third side of the triangle | `./gradlew :guardrails:test` |
| `ScreenshotCoverageTest` | a screen with a screenshot in only one theme, or with no golden | `./gradlew :guardrails:test` |
| the ATF check inside `ScreenshotTest.capture` | a touch target too small, or an interactive element with no screen-reader label — on **every** screen and in **both** themes | runs with each `:feature:*` and `:core:ui` test |
| `BackupExclusionTest` | a private folder under `filesDir` ending up in a cloud backup — derived from the sources, not a hand-written list | `./gradlew :guardrails:test` |
| `StoreCatalogTest` | a store with a real adapter but no description in the 5 languages, no registry entry, no row in the table above, **or not receiving its configuration through the remote override** — the list is derived from the modules that have sources | `./gradlew :guardrails:test` |
| `checkDependencyRules` | a module dependency violating the architecture | `./gradlew checkDependencyRules` |

Recording goldens after adding or changing a screen: `./gradlew recordRoborazziDebug`. Comparing them:
`./gradlew verifyRoborazziDebug`.

**A tenth check is not a guardrail, and the difference matters:** `canaryTest` runs the parsers against the
**real** sites, so it is neither offline nor deterministic and cannot block anything. It runs nightly and opens
an issue. The task exists only on `:store:*` modules and its tests are tagged `@Tag("canary")`, which `test`
excludes — a unit test never touches the network.

**All nine canaries exist, including F-Droid's**, and that one arrived last for a plausible, wrong reason: "it is
not a scraped store, there is no markup to change". True, and beside the point: what can break silently there is
not a selector but the **pinned certificate** the repository signs its index with, and if it changes the index is
discarded and with it search, detail, updates and categories for the whole store. It is also the only canary that
can compare a **published** hash with the downloaded bytes.

Two things about it that generalise: **the pin has no assertion of its own, and that is better** — `openIndex`
fails if the certificate is not the expected one, so that call succeeding *is* the pin's verification, whereas a
separate comparison would re-read our own constant and compare it with itself. And **the index is downloaded once
for the whole class**, because it is 57 MB and two tests need it; the artifact the hash is checked on is **the
smallest in the index**, chosen by the index itself rather than named by hand, because a package written here can
disappear from F-Droid without anything interesting having happened.

### Accessibility: the check sits where every screen passes

From the screenshot capture, the composition is also passed to the **Accessibility Test Framework**, the same
engine Espresso and Accessibility Scanner use. The place was chosen: `ScreenshotTest.capture` is the bottleneck
every screen in both themes passes through, and a guardrail already forces them through it. A check hooked
anywhere else would be a hand-maintained list of screens, that is a list that eventually is not.

Before it there was nothing: 12 `contentDescription`s across seven feature modules, five of them `null`, and two
occurrences of `semantics` in the whole app.

Four things to know before touching it:

- **all roots are checked, not `onRoot()`.** A screen with a `ModalBottomSheet` or a dialog has **two** Compose
  roots, and there `onRoot()` is not ambiguous: it fails. Taking one would mean taking the one underneath and
  skipping the very surface the user is touching.
- **contrast is not covered**, and that is measured: `#FAFAFA` text on white and `#FFFF33` at 28sp on `#FFFF00`
  both pass. The engine does receive the bitmap, but for Compose nodes the contrast checks produce no result.
  **This is written down instead of left to be assumed**: a guardrail believed broader than it is makes people
  stop looking. Contrast is governed by rule 3 — colours only from `MaterialTheme.colorScheme` — and by the
  goldens in both themes.
- **there is exactly one suppression, and it is narrow**: the size of a target the **window edge clips**. A
  screenshot is a still frame of a scrolling surface, and there is no scroll position where nothing is clipped —
  the one that frees the bottom clips the top. A small target in the middle of the screen stays red, and that is
  verified by injection.
- **`Error`, not `Warning`**, and `AccessibilityCheckPreset.LATEST` rather than a hand-picked list: a fixed list
  ages silently, and a warning that stops nothing is a written rule.

Across ten captures the check found exactly one thing, and it was real: Material 3's `ModalBottomSheet` handle is
32dp × 48dp — Compose gives it the minimum *height* the guidelines ask for, not the width. It carries the
expand/collapse action, so it is a real target. Widening it with `sizeIn(minWidth = 48.dp)` **widens the drawing
too**, because the minimum constraint crosses the `Surface` down to the inner `Box`: sixteen density-independent
pixels of extra bar is a price gladly paid; a hand-rewritten handle to save them is not.

### The release build has to be run, not just compiled

R8 only runs in release. Libraries that resolve by name — protobuf-lite, Room, Retrofit,
kotlinx.serialization, Jsoup, apksig — therefore fail in a way that **never shows during development**. It has
happened three times here:

1. R8 renamed `themeMode_` to `e` and the first DataStore read went to `NoSuchFieldException`, with the debug
   build working perfectly;
2. **R8 full mode strips annotations from classes no `-keep` covers**, and `-keepattributes
   RuntimeVisibleAnnotations` alone is not enough. `apksig` decodes ASN.1 by reading `@Asn1Class`/`@Asn1Field`
   annotations off its PKCS#7 and X.509 models at runtime: the classes stayed in the APK, the annotations did
   not, and every ASN.1 parse died. Where it was visible there was a fallback and the outcome was harmless; in
   **v1 signature verification** there is no fallback;
3. `ShizukuShell` starts the privileged process with `Shizuku.class.getDeclaredMethod("newProcess", …)`, because
   that method exists but is `private`. Reflection by name is exactly what R8 does not see, and without the keep
   the Shizuku channel would come out absent **only in the build users install**.

Rule: **when you add a library that uses reflection or names, install and open the minified build before saying
it works.** A green `assembleRelease` proves nothing: R8 does not fail at compile time, it fails at the first
user. And `usage.txt` needs reading carefully — it says "removed" even of a static enum field whose object stays
alive inside `$VALUES`; telling the two cases apart needs the disassembled `<clinit>`.

**R8 rules are carried by the module that has the dependency**, with `consumerProguardFiles`, not by `:app`. Same
reason as the permissions: from there it is visible why they exist, and whoever adds the module elsewhere gets
them without having to know.

### The first migration, and how to test it without `MigrationTestHelper`

The schema was born complete — including the tables nobody filled yet — precisely so that none would need
migrating between milestones. The database is built **without** `fallbackToDestructiveMigration`, so a version
bumped without its migration does not wipe the data: it does not open the database, and the app does not start on
the first launch after the update.

`MigrationTestHelper` requires an instrumented test and reads schemas from `androidTest` assets; there is no
instrumented source set here and everything is tested with Robolectric. `MigrationTest` therefore rebuilds the
previous version by **executing the committed schema's `createSql`** (passed to the tests as the
`multistore.schemaDir` system property), inserts a row, opens with Room and checks it is still there. The source
is the real schema, not a hand-copied list of `CREATE TABLE`s nobody would realign.

Three different mistakes turn it red: a missing migration, a migration producing a column different from the
entity's (Room says so, comparing the resulting schema with the expected one after migrating), and a migration
that recreates the table losing the rows — the last caught by the data assertion.

One thing `ALTER TABLE` does not forgive: **a `NOT NULL` column requires a `DEFAULT`**, and a default declared in
the migration but not in the entity is a mismatch Room reports at open time, that is on the user's device. When
in doubt, nullable column — and where that is not possible, the default has to be declared **twice**: in the
migration and in `@ColumnInfo(defaultValue = …)`.

**And a migration that adds a column does not fill the rows that were already there.** It is correct and leaves
the app in a wrong state: measured on an emulator, 4,278 rows out of 4,278 at `UNKNOWN`, that is a filter finding
nothing until the next resync — seven days. Before closing a migration, the question to ask is **where that value
is already written**: there it was in another table, and an `UPDATE` carried it over.

### Fault injection, and its five faces

Every guardrail here was verified by injecting the violation it must catch. But an injection can come back green
for four different reasons, and telling them apart matters:

1. **a missing test** — the defence is real and no fixture exercises it. The answer is a fixture, not removing
   the defence;
2. **a defence that does not exist** — the clause protects nothing because something else already does. It may
   stay as correct scoping, but the comment next to it must then say which one carries the weight;
3. **a defect no longer expressible** — writer and sweeper read the same constant, so they move together. The
   useful check there is *who else* knows that string: a second copy in another module is where the divergence
   would live;
4. **two variants equivalent on every real input** — `indexOf` and `lastIndexOf` give the same answer on every
   name that actually occurs. Something else carries the weight, and the comment has to say what.

And a fifth face, about the harness rather than the code:

> An injection that prints nothing is not an injection that passes: **it is an injection that was not run.** And
> an injection that fails without naming a test is not an injection that protects: **it is a broken build.** Look
> at the test's name, not at the build's colour.

Four ways that has actually gone wrong here: running `--tests` against an Android module's aggregate `test` task
(which does not accept it, so the build failed for the wrong reason and nine injections were reported red without
a single test failing); a script whose regex could not match JUnit 5 `@DisplayName`s containing spaces, so a red
injection was reported green; a missing `--rerun`, letting Gradle declare the task `UP-TO-DATE` so the injection
never ran (the tell is the time: a module that usually takes eight seconds taking one has not run); and an anchor
string occurring **twice** in a file, so the injection landed in the wrong method and reported a defence proven
that nobody had touched. **The anchor is counted before being substituted, and if it is not unique the injection
is reported as a broken harness, never as green.**

**An injection can also be the counter-proof of another, and then green is the expected result.** The
accessibility check was tested in a pair: first "put the 32dp handle back" alone → **red**, then the same thing
together with "lower the level to `LogOnly`", with "check only one root", and with "widen the suppression to
everything" → **green** all three. Together the four runs say which mechanisms catch that defect, one at a time. A
green counter-proof has to be **labelled as such**, though: a list where it cannot be told from a failed
injection is a list nobody will revisit.

**A branch no configuration walks is a branch nobody tests.** `DownloadMode.USER_ASSISTED_ONLY` existed from the
start and `:feature:webviewdownload` nearly as long, but until uptodown every adapter was `DIRECT`: the return
from the assisted path was broken in the last metre — the file came down whole and the page sat on the "open the
store page" notice, with no verification and no installation. When you add the **first** implementer of a
declared capability, the thing to test is not the adapter: it is the branch that capability switches on.

### Some things that only fail in specific conditions

**Virtual time and Room do not mix, and the way it fails misleads.** A test measuring *time* uses `runTest`'s
virtual clock, whose rule is that the scheduler advances as soon as it has nothing left to run. With normal
executors Room's queries run on real threads: while one is in flight the scheduler does not see it, concludes
there is nothing to do, and jumps straight to the timeout. The outcome is a red test accusing the wrong code — in
the concrete case *every* store came out timed out, including the instantaneous one, and the defect looked like
the fan-out's. The fix is in the test database (`setQueryExecutor(Runnable::run)`,
`setTransactionExecutor(Runnable::run)`), and it is needed **only** where time is the subject.

**`weight` inside a `LazyColumn` is zero, and the golden said so.** `Modifier.weight` distributes the *remaining*
space, and inside a lazy column the parent's height is unconstrained: the share is zero. Moving the Home's body
into a lazy list made a title and description **disappear**, leaving a lone button under a white space. Not an
error a green suite catches: the layout compiles, does not throw, and the ViewModel test keeps passing. The
Roborazzi golden saw it — which is why rule 3 asks for a screenshot per screen rather than trusting a state test.

**A `Column` that does not scroll squashes the last row, and accessibility said so.** Adding an entry to a
section turned a golden red with "This item's height is 40dp" on a switch, while eight identical ones passed. A
non-scrolling `Column` measures its children with the height that is left, and when that runs out the last row is
compressed. The check was right twice over, because in such a column that row would not even be reachable. **A
target that shrinks as the content grows is not a minimum-size problem, it is a layout that does not fit.**

**A WebView cannot be tested, so what can be is isolated.** Same split as `ShellInstaller` over
`PrivilegedShell`: on the JVM a WebView does not exist, and under Robolectric the shadow executes no JavaScript.
`SilentChallengeEngine` is that line. Behind it sits the Android implementation, blind by construction; in front
of it sits `WebViewSilentResolver`, which is the **protocol** and tests on the JVM with a fake engine and a
MockWebServer. **And the proof that the cookie transfer happens is not a queue of responses.** The fake Cloudflare
in the test looks at the `Cookie` header: 403 until the clearance arrives, 200 as soon as it does. With a queued
"403, 403, 200" the test would be green even with the transfer removed, because what passed would be the third
response and not the cookie.

**In Paging tests, use a page with declared `sourceLoadStates`.** With the one-argument
`PagingData.from(rows)` form, `asSnapshot()` never returns and the test fails with `UncompletedCoroutinesError`,
accusing the wrong code. What unblocks it is **declaring `sourceLoadStates`**, not the value put in them — proven
by injection, and written next to the helper so nobody believes it is `endOfPaginationReached = true` doing the
work.

### General rules

- Unit tests **never touch the network**. Ever.
- No `Thread.sleep` in tests. Clocks and dispatchers are injected.
- Every adapter: real saved fixtures + `StoreAdapterContractTest`.
- Network/breaker: MockWebServer + injected clock.
- Room: a migration test for every schema bump.
- Pre-install verification: APK fixtures for the four cases (valid, wrong packageName, different signature, wrong
  hash).
- Compose: UI tests on the flows + Roborazzi light/dark on every screen.
- A nightly canary against the real sites: **non-blocking**, opens an issue when an adapter breaks.

---

## Toolchain

- Gradle 9.6 · **AGP 9.3.1** · **Kotlin 2.3.21** · **KSP 2.3.11**
- `minSdk 26`, `targetSdk 36`, **`compileSdk 37`**. Bytecode target 17 everywhere, Kotlin and Java.
- Version catalog (`gradle/libs.versions.toml`) + convention plugins in `build-logic/`. **Never** declare a
  dependency version inline in a module's `build.gradle.kts`.

Four constraints that were measured, not deduced. Changing them without re-measuring breaks the build:

1. **AGP 9 has built-in Kotlin support.** Applying `org.jetbrains.kotlin.android` fails with an explicit error.
   The `kotlin { compilerOptions { … } }` extension is still available, but **`kotlin.sourceSets` cannot be used**
   to add sources: `android.sourceSets` is. Pure JVM modules keep applying `org.jetbrains.kotlin.jvm`.
2. **KSP ≥ 2.3 is mandatory.** The version AGP 9.3.1 brings with it registers generated sources through
   `kotlin.sourceSets` and therefore **does not compile** with built-in Kotlin.
3. **Kotlin aligned with Gradle 9.6's embedded Kotlin (2.3.21).** The precompiled script plugins in
   `build-logic/` are compiled by Gradle's own Kotlin: a newer KGP produces metadata that compiler cannot read.
4. **`compileSdk 37` while `targetSdk` stays 36.** Current AndroidX requires 37 and the build stops before
   compiling otherwise. `compileSdk` only decides which APIs are reachable; runtime behaviour is decided by
   `targetSdk`.

### Running it

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

emulator -avd <your-avd> -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect &
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 1; done

# animations off: otherwise screenshots catch intermediate frames
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global $k 0
done

adb shell am start -n com.multistore.debug/com.multistore.app.MainActivity
adb logcat -d -b crash | tail -30   # crashes: the dedicated buffer, not the general logcat
```

Two things that waste time if you do not know them:

- **do not wait "by time" after `am start`.** The first launch after an installation includes profile
  installation and is much slower than later ones: you end up photographing the splash and concluding the app is
  stuck. Loop until the screenshot stops being the splash, or query `dumpsys activity activities`.
- **the Gradle console abbreviates.** `lint` prints only the first error in detail and closes with "Lint found N
  errors": reading the on-screen list and counting it leads to wrong conclusions. The full reports are in
  `<module>/build/reports/lint-results-*.xml`. The same goes for R8: `mapping.txt` can be a leftover from a
  previous run — AGP 9 writes `mapping.prt` — and what really says which symbols are protected is `seeds.txt`.
