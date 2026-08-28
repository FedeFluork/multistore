package com.multistore.core.model

/**
 * The 5 languages MultiStore's interface exists in completely.
 *
 * Every user-visible string is added to all 5 at once. This enum is the executable form of that
 * list: the translation-parity test and the language picker both read from here, so they cannot
 * diverge.
 *
 * NOTE: adding an entry here means adding a fully translated `values-<tag>/strings.xml`, or
 * `TranslationParityTest` fails — which is exactly the intended effect.
 */
enum class SupportedLanguage(
    /** BCP-47, as expected by `AppCompatDelegate.setApplicationLocales`. */
    val tag: String,
    /** The language's name in that language: readable by someone who cannot read the current one. */
    val endonym: String,
) {
    ENGLISH("en", "English"),
    ITALIAN("it", "Italiano"),
    FRENCH("fr", "Français"),
    SPANISH("es", "Español"),
    GERMAN("de", "Deutsch"),
    ;

    companion object {
        /** Fallback language when the system one is not among the supported ones. */
        val FALLBACK: SupportedLanguage = ENGLISH

        /** Empty tag = "follow the system", the default on first launch. */
        const val FOLLOW_SYSTEM_TAG: String = ""

        fun fromTagOrNull(tag: String): SupportedLanguage? =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

        /**
         * Like [fromTagOrNull] but tolerant of the region subtag.
         *
         * Needed when reading the language the user chose in system settings: `LocaleManager`
         * can return `it-IT` or `fr-CA`, while we reason per language. Without this, an `it-IT`
         * read from the system would go unrecognised and be treated as "follow the system".
         */
        fun fromBcp47OrNull(tag: String): SupportedLanguage? =
            fromTagOrNull(tag) ?: fromTagOrNull(tag.substringBefore('-'))
    }
}
