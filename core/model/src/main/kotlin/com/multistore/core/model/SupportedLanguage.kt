package com.multistore.core.model

/**
 * The 6 languages MultiStore's interface exists in completely.
 *
 * Every user-visible string is added to all 6 at once. This enum is the executable form of that
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
    CHINESE_SIMPLIFIED("zh-CN", "简体中文"),
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
         * can return `it-IT`, `fr-CA` or `zh-Hans-CN`, while we reason per language. Without this,
         * a regional or script variant would go unrecognised and be treated as "follow the system".
         */
        fun fromBcp47OrNull(tag: String): SupportedLanguage? =
            fromTagOrNull(tag)
                ?: entries.firstOrNull {
                    it.tag.substringBefore('-').equals(tag.substringBefore('-'), ignoreCase = true)
                }
    }
}
