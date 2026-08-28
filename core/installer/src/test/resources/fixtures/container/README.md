# Split container fixtures

Two **real** metadata files, taken from two real containers downloaded on 26/08/2026 with the app's
client (OkHttp, HTTP/2, Chrome mobile UA) and not with `curl` — on apkmirror `curl` gets 403, and the
rule is that whoever verifies with `curl` is measuring `curl`.

| File | From | Container | Bytes |
|---|---|---|---|
| `xapk-manifest.json` | `apks.…r2.cloudflarestorage.com/com.duolingo/6.93.6/2440.….apks` via `apkcombo.com/duolingo/com.duolingo/download/apk` | XAPK, `xapk_version` 2, 15 entries, **`store` compression** | 238,820,999 |
| `apkm-info.json` | `www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=15507577` | APKM, `apkm_version` 5, 12 entries, **`deflate` compression** (624 MB uncompressed) | 286,519,098 |

**Why only the metadata and not the whole containers.** Two hundred and forty and two hundred and
eighty megabytes do not fit in a repository, and shrinking them by hand would make them fake in
precisely the part that counts. The cut is therefore clean: **the metadata are real**, and they are
the only part a parser interprets; the APKs inside the tests' containers are the signed fixtures from
`../apk/`, which are real APKs with a known package, version and signer — i.e. exactly what step 8 of
verification compares.

What the two files demonstrate, and which no documentation guaranteed:

- the base **is not called the same thing** in the two formats: `com.duolingo.apk` (i.e.
  `<packageName>.apk`) against `base.apk`. That is why the XAPK declares it with `id: "base"` in
  `split_apks`, and it is not deduced from the name;
- `apkm_version` is a **number**, `xapk_version` a **string** (`"2"`). The same holds for
  `versioncode` (number) against `version_code` (`"2440"`);
- **neither carries `expansions`.** The only OBB measured across the nine stores is an1's, which does
  not sit in a container but on a second download page (`/file_{id}-dw_cache.html`): a zip containing
  `com.rockstargames.gtactw/main.4.com.rockstargames.gtactw.obb`, 906,553,432 bytes uncompressed. The
  tests' container with expansions is therefore **constructed**, and the test says so.
