package com.multistore.core.common.text

import java.text.Normalizer
import java.util.Locale

/**
 * Title normalisation, for cross-store identity.
 *
 * It matters even with a single store — searching the local index compares typed text against
 * titles — and becomes central across stores, where "the same app" has to be recognised between
 * sites that write it differently: `Telegram`, `Telegram MOD APK`,
 * `Telegram (Premium Unlocked) v10.2.1`.
 *
 * Pure Kotlin: it uses `java.text.Normalizer`, identical on the JVM and on Android.
 */
object TextNormalizer {

    /**
     * The noise stores add to titles.
     *
     * Order matters: longer patterns come first, otherwise `MOD` eats `MOD APK` and leaves `APK`
     * orphaned.
     */
    private val NOISE = listOf(
        Regex("""\bmod\s*apk\b"""),
        Regex("""\bfull\s*apk\b"""),
        Regex("""\bpro\s*apk\b"""),
        Regex("""\bpremium\s*unlocked\b"""),
        Regex("""\bunlocked\b"""),
        Regex("""\bcracked\b"""),
        Regex("""\bpatched\b"""),
        Regex("""\bapk\b"""),
        Regex("""\bmod\b"""),
        Regex("""\bandroid\b"""),
        Regex("""\bfree\s*download\b"""),
        Regex("""\bdownload\b"""),
        Regex("""\blatest\s*version\b"""),
    )

    /** A version number in brackets or prefixed with `v`: `(v10.2.1)`, `v2.3`, `1.0.4`. */
    private val VERSION = Regex("""[\(\[]?\bv?\d+(\.\d+)+[a-z]?[\)\]]?""")

    private val DIACRITIC = Regex("""\p{Mn}+""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")

    /**
     * The form in which two titles are compared: lowercase, no diacritics, no noise, no
     * punctuation, whitespace collapsed.
     *
     * `"Télegram MOD APK v10.2.1"` and `"Telegram"` both become `"telegram"`.
     */
    fun normalizeTitle(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = DIACRITIC.replace(s, "")
        s = VERSION.replace(s, " ")
        for (pattern in NOISE) s = pattern.replace(s, " ")
        s = NON_ALNUM.replace(s, " ")
        return s.trim().replace(Regex("""\s+"""), " ")
    }

    /** Like [normalizeTitle] but without spaces: useful as an index key. */
    fun titleKey(raw: String): String = normalizeTitle(raw).replace(" ", "")

    /**
     * Prepares a search term typed by the user.
     *
     * It does not strip noise: someone searching for "mod" probably means it.
     */
    fun normalizeQuery(raw: String): String {
        var s = raw.lowercase(Locale.ROOT).trim()
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = DIACRITIC.replace(s, "")
        return s.replace(Regex("""\s+"""), " ")
    }
}
