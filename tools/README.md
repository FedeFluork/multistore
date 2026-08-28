# tools

What produces and signs the documents the app downloads. It is not part of the APK.

## `sign-config.sh`

Wraps a JSON document in an **Ed25519-signed envelope**, in the shape `:core:remoteconfig` knows
how to open.

```sh
tools/sign-config.sh payload.json .secrets/parsers-ed25519.pem parsers.json
```

The envelope is `{"algorithm":"ed25519","payload":"<base64>","signature":"<base64>"}`. The payload
travels base64-encoded because **what is signed is a sequence of bytes, not an object**: this way
the bytes verified and the bytes interpreted are the same, and there is no canonical form for the
signer and the verifier to agree on.

## `release.sh` — the signed release, and the `selfUpdate` block it produces

```sh
MULTISTORE_RELEASE_URL="https://…/multistore-0.6.0.apk" tools/release.sh build/self-update.json
```

It builds `:app:assembleRelease`, **verifies that it is signed** and reads `versionCode`,
`versionName`, `minSdk`, size and SHA-256 **from the APK**, not from `build.gradle.kts`. The two
coincide until somebody gets it wrong, and this script exists for that case: those numbers end up in
a **signed** document that every installation applies, and a wrong hash there does not produce a
syntax error — it produces an update everybody downloads and that pre-install verification refuses.

It also prints the **signer**, which is the value the app will compare against the already-installed
certificate. If it does not match, the update is refused and whoever has the app installed has no
way out other than uninstalling, losing their data.

### The distribution key

It is not in the repository and it is not mandatory: without `.secrets/keystore.properties` the
`release` variant comes out **unsigned**, and `release.sh` stops and says why. A release signed with
the debug key would be worse than an unsigned one, because it would install.

```sh
keytool -genkeypair -v -keystore .secrets/multistore-release.jks \
  -alias multistore -keyalg RSA -keysize 4096 -validity 10000
```

```properties
# .secrets/keystore.properties
storeFile=/absolute/path/.secrets/multistore-release.jks
storePassword=…
keyAlias=multistore
keyPassword=…
```

**This key must not be changed and must not be lost.** It is the identity every installation is tied
to: changing it means no device will be able to update the app any more, because Android refuses an
update signed by a different key. It is the same property `PreInstallVerifier` checks at step 5, seen
from the other side.

The properties file exists instead of four `-P` flags because a password on the command line ends up
in the shell history and in the process list.

## Publishing a version, start to finish

1. bump `versionCode` and `versionName` in `app/build.gradle.kts`;
2. `./gradlew build checkDependencyRules lint verifyRoborazziDebug` — green;
3. **`./gradlew :app:installMinified` and open it.** Not a luxury: R8 only runs in release, and a
   library that resolves by name fails in a way the debug build never shows (it has happened three
   times in this project);
4. `MULTISTORE_RELEASE_URL=… tools/release.sh build/self-update.json`;
5. upload the APK to that address;
6. `./gradlew :tools:index:buildIndex --args="build/index-payload.json <ISO8601> build/self-update.json"`;
7. `tools/sign-config.sh build/index-payload.json .secrets/parsers-ed25519.pem index.json`;
8. publish `index.json`;
9. **test it with a minified build**, and test the refusal too: the same payload signed with a
   different key must apply nothing. Without the second experiment the first only says "it read
   something".

Step 8 is the only one that has no destination today: the pinned constant does not resolve, which is
a decision requiring an account rather than code. Everything before and after it works: turning the
channel on will not require touching the app.

## `index/` — the pipeline that produces `index.json`

It is a Gradle module, `:tools:index`, and **it does not go into the APK**: `:app` does not depend on
it.

```sh
./gradlew :tools:index:buildIndex --args="build/index-payload.json 2026-08-25T21:00:00Z [release.json]"
tools/sign-config.sh build/index-payload.json .secrets/parsers-ed25519.pem index.json
```

The third argument is optional and is the `selfUpdate` block, produced by
[`release.sh`](#releasesh--the-signed-release-and-the-selfupdate-block-it-produces): only whoever
built the APK knows those values, and they used to be copied by hand into a signed document.

### Why a Gradle module and not a script

A script outside Gradle would have had to **redo the parsers** of two RSS feeds, a ranking page and a
JSON-LD block, and then deduplicate by app what apkmirror publishes per release. Those would have
been parsers no fixture covers and no canary watches, bound to drift silently from the real ones —
and the first symptom would have been a **signed** `index.json` full of wrong data, which every
installation applies.

As a module, the pipeline calls the real adapters: when a store changes markup it breaks together
with the app, and the nightly canary already covers it.

### What it reads, and from whom

Five stores out of nine, because only five publish a readable surface:

| Section | Source | Entries |
|---|---|---|
| `popular` | uptodown `/android/top` | 10, with the rank stated inside the title |
| `popular` | apkmody `/popular` | 12, from the schema.org `ItemList` block |
| `recent` | apkcombo `/latest-updates/feed` | ~96, **with the `packageName` in the URL** |
| `recent` | apkmirror `/feed/` | 10, with the developer in the title |
| `recent` | pdalife `/rss/` | 100, all Android |
| `recent` | uptodown `/android/latest-updates` | 48, same container as search |

Who is absent, and why: F-Droid publishes no popularity and the app already has its new releases in
the local index; an1 serves the **homepage** in place of `/popular/` and its `rss.xml` has a single
entry, "RSS in offline mode"; liteapks has neither; **modyolo has a feed that was deliberately not
used** — see the section below.

### The adult-content rule, and the measurement that decided it

modyolo's `/feed/` had 24 entries of which **six were adult content — and all six filed under "Role
Playing"**, that is outside the six categories that store declares adult. `show_nsfw_content` filters
**the label**, and on that surface the label is not there: the setting would remove nothing.

The Home is a surface the app chooses **without anybody having asked for anything**, hence the
general rule: *a filter that reads a label does not protect a source that does not label*. The only
defence is choosing the source, and choosing it by looking at what is actually on it. The other five
surfaces were inspected and are clean.

## The keys

The **public** key is pinned in `ParsersKey.PUBLIC_BASE64` and is versioned: it is what lets the app
refuse a document we did not sign. The **private** one is not in the repository — `.gitignore`
excludes `.secrets/` — and whoever holds it can publish selectors and domains that every installation
will apply as its own.

Generating a new one (it changes the pinned key, and therefore invalidates every document already
published):

```sh
openssl genpkey -algorithm ed25519 -out .secrets/parsers-ed25519.pem
chmod 600 .secrets/parsers-ed25519.pem
openssl pkey -in .secrets/parsers-ed25519.pem -pubout -outform DER | tail -c 32 | base64
```

The last 32 bytes of the DER `SubjectPublicKeyInfo` **are** the raw key: for Ed25519 that DER is 44
bytes long and the first 12 are the algorithm header.

## The format of `index.json`

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-08-25T21:00:00Z",
  "popular": [ { "store": "uptodown", "ref": "capcut", "title": "CapCut", "sources": 2 } ],
  "recent":  [ { "store": "apkcombo", "ref": "recovery-reboot/gt.recovery.reboot", "title": "Recovery Reboot" } ],
  "stores":  [ { "store": "an1", "reachable": false, "detail": "blocked:forbidden" } ],
  "selfUpdate": { "versionCode": 2, "versionName": "0.5.0", "url": "…", "sha256": "…" }
}
```

Three things distinguish it from `parsers.json`, and each is a decision:

- **it applies immediately**, without waiting for a restart. It is content, not configuration:
  nobody interprets it, it is simply rendered. `parsers.json` does the opposite because the adapters
  receive their selectors from the constructor, and changing them halfway through a search would mean
  interpreting with one configuration a page downloaded with another;
- **an unknown key inside an entry does not bring the document down.** There it is the opposite, and
  that too for a reason: at the outermost level an unknown key means a different schema, which
  `schemaVersion` exists to declare. Here it is a field a newer pipeline has added, and discarding the
  whole index would mean an empty Home for whoever has not updated the app yet;
- **`stores[]` lists only those that did not answer.** Nine rows saying "all fine" would be noise in a
  document every installation downloads.

The `selfUpdate` block passes through the pipeline **without being reinterpreted**: whoever produces
it reads it from the APK, and rewriting it would be a second chance to get the hash wrong.

## The format of `parsers.json`

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-08-25T09:12:00Z",
  "stores": {
    "uptodown": { "selectors": { "searchItem": "#content-list .card" } },
    "apkmirror": { "baseUrl": "https://www.apkmirror.com" }
  }
}
```

The keys of `stores` are the `StoreId.wireName`s. Each value is a **partial override** of that
store's compiled configuration: whatever is not named stays as it is.

Three things the app does that are worth knowing before publishing:

- a key the compiled configuration does not have is **discarded and counted**, and the Settings
  screen lists it with its path. A typo applies nothing and does not go unnoticed;
- a value of the wrong type costs **that store's** override, not the whole document;
- an accepted document becomes active at the app's **next restart**, not immediately.
