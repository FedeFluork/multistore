package com.multistore.store.fdroid

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The F-Droid fixtures: **real** files, downloaded from `f-droid.org` on 23/08/2026 and committed.
 *
 * They are not invented, and that is the point. A parser proven on hand-written JSON proves the
 * parser can read what whoever wrote it imagined; proven on the real index it proves it can read the
 * 45 non-standard file names, the 3 `.zip` entries and the two identical version codes with
 * different signatures — things nobody would invent.
 *
 * `index-v2-slice.json` is a slice of the real index: 12 packages and 48 versions, chosen one by one
 * **for their traps** rather than at random, with the translation maps reduced to a realistic subset
 * of languages to keep the file under 200 KB.
 */
object Fixtures {

    private const val DIRECTORY = "fixtures/fdroid"

    val json: Json = Json { ignoreUnknownKeys = true }

    fun file(name: String): File {
        val url = requireNotNull(Fixtures::class.java.classLoader.getResource("$DIRECTORY/$name")) {
            "Missing fixture: $DIRECTORY/$name"
        }
        return File(url.toURI())
    }

    fun text(name: String): String = file(name).readText()

    fun jsonObject(name: String): JsonObject =
        json.parseToJsonElement(text(name)) as JsonObject

    /** The index slice's packages, by name. */
    fun slicePackages(): Map<String, JsonObject> {
        val root = jsonObject(INDEX_SLICE)
        val packages = root.getValue("packages") as JsonObject
        return packages.mapValues { (_, v) -> v as JsonObject }
    }

    fun slicePackage(packageName: String): JsonObject =
        requireNotNull(slicePackages()[packageName]) {
            "The index slice does not contain $packageName. If it has been regenerated, update " +
                "the test rather than softening it: that package was there for a reason."
        }

    const val ENTRY_JAR: String = "entry.jar"
    const val ENTRY_JAR_TAMPERED: String = "entry-tampered.jar"
    const val ENTRY_JAR_UNSIGNED: String = "entry-unsigned.jar"
    const val ENTRY_JAR_FOREIGN: String = "entry-foreign.jar"
    const val ENTRY_JSON: String = "entry.json"
    const val INDEX_SLICE: String = "index-v2-slice.json"
    const val INDEX_EMPTY: String = "index-v2-empty.json"
    const val INDEX_TRUNCATED: String = "index-v2-truncated.json"
    const val DIFF_SLICE: String = "diff-slice.json"
    const val SIGNER_INDEX: String = "signer-index-slice.json"
    const val API_PACKAGES: String = "api-packages-org.fdroid.fdroid.json"
    const val SEARCH_APPS: String = "search-apps-fdroid.json"
    const val SEARCH_APPS_EMPTY: String = "search-apps-empty.json"

    // The packages chosen for their traps, with the reason each is in the slice.
    /** `Beta` channel: the highest version code (2000040, `2.0-rc0`) is not the one to offer. */
    const val PKG_FDROID: String = "org.fdroid.fdroid"

    /** The 3 `.zip` entries: no signer, no `usesSdk`, no `preferredSigner`. */
    const val PKG_OTA: String = "org.fdroid.fdroid.privileged.ota"

    /** Two signers, and **the same versionCode 50 published twice**. */
    const val PKG_KEYBOARD: String = "juloo.keyboard2"

    /** Two signers, with the Wear variant on a lower version code. */
    const val PKG_CATIMA: String = "me.hackerchick.catima"

    /** Non-canonical file names: `<pkg>_<versionCode>_<githash>.apk`. */
    const val PKG_BANGLEJS: String = "com.espruino.gadgetbridge.banglejs"

    /** One build per ABI, and version-level anti-features. */
    const val PKG_NEWPIPE: String = "InfinityLoop1309.NewPipeEnhanced"

    /** Three anti-features on the same version. */
    const val PKG_PROTONVPN: String = "ch.protonvpn.android"

    /** Has the summary in Italian but **not** in `en-US`: it exercises the fallback. */
    const val PKG_STREETCOMPLETE: String = "de.westnordost.streetcomplete"

    /** Without icon. */
    const val PKG_NO_ICON: String = "agrigolo.chubbyclick"

    /** With neither summary nor description. */
    const val PKG_NO_TEXT: String = "app.fedilab.nitterizeme"

    /** `minSdkVersion` 33: incompatible with an older device. */
    const val PKG_MIN_SDK_33: String = "com.angrydoughnuts.android.alarmclock"

    /** A single version, and an id that looks like anything but a package name. */
    const val PKG_SNAKE: String = "S.N.A.K.E"
}
