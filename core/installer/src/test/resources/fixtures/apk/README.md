# APK fixtures for the pre-install verification pipeline

Four real APKs, generated with `aapt2 link` + `apksigner` (build-tools 36.1.0) and committed: they
are minimal (8.5 KB) and cover the four outcomes the verification pipeline has to be tested against.
They contain no code (`android:hasCode="false"`): they exist only to be inspected.

| File | packageName | versionCode | signer (certificate SHA-256) | What must happen |
|---|---|---|---|---|
| `valid.apk` | `com.multistore.fixture.valid` | 42 | `1344fc7558b8753078370136765c2f046ad24cd018befaeee8f4c9f226ecc391` | passes |
| `wrong-package.apk` | `com.multistore.fixture.other` | 42 | `1344fc75…` (the same as `valid`) | **hard block**: packageName differs from the listing's |
| `foreign-signer.apk` | `com.multistore.fixture.valid` | 42 | `e7479abb76fdeba6761b8b9b3ae0d533b1c3b2286d7577f67dec0e35cda17395` | blocked if the package is already installed with a different signature |
| `unsigned.apk` | `com.multistore.fixture.valid` | 42 | — none | refused: an unsigned APK is not installable |

The fifth case — a wrong hash — needs no file of its own: it is obtained by passing an
`expectedSha256` different from the file's. The files' SHA-256 values live in the test, not here, so
that a replaced file makes the test fail instead of making this README lie.

The two keys were generated once with `keytool` and the keystores are **not** committed: the fixtures
are the signed binaries, not the ability to sign more.
