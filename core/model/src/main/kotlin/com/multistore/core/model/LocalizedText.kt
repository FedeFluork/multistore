package com.multistore.core.model

/**
 * Text a store publishes in several languages.
 *
 * F-Droid publishes name, summary, description, icon and screenshots as `{BCP-47 tag -> value}`
 * maps. In the real index the dominant key is **`en-US`, not `en`** (12,634 occurrences against
 * 42), and `de` coexists with `de-DE`, `fr` with `fr-FR`: resolving by exact equality would leave
 * an Italian user reading English even where the Italian translation exists.
 *
 * Resolution lives here, in pure Kotlin, because it is pure logic and must be tested as such:
 * `android.os.LocaleList` is not needed and would force Robolectric on us.
 */
@JvmInline
value class LocalizedText(val byTag: Map<String, String>) {

    val isEmpty: Boolean get() = byTag.isEmpty()

    /**
     * The text in the language closest to [preferredTags], in order.
     *
     * The ladder, for each preferred tag before moving to the next:
     *  1. exact match (`it-IT` -> `it-IT`), case-insensitive;
     *  2. same language without region (`it-IT` -> `it`);
     *  3. same language with **any** region (`it` -> `it-IT`), in stable alphabetical order:
     *     without an order, the same app would show `pt-BR` or `pt-PT` at random;
     *  4. then English through the same ladder, the project's declared fallback;
     *  5. finally the first available entry: text in a language you cannot read beats an empty
     *     listing.
     */
    fun resolve(preferredTags: List<String>): String? {
        if (byTag.isEmpty()) return null
        val chain = preferredTags + SupportedLanguage.FALLBACK.tag
        for (tag in chain) {
            match(tag)?.let { return it }
        }
        return byTag.entries.minByOrNull { it.key }?.value
    }

    private fun match(tag: String): String? {
        val wanted = tag.lowercase()
        if (wanted.isEmpty()) return null
        byTag.entries.firstOrNull { it.key.lowercase() == wanted }?.let { return it.value }
        val language = wanted.substringBefore('-')
        byTag.entries.firstOrNull { it.key.lowercase() == language }?.let { return it.value }
        return byTag.entries
            .filter { it.key.lowercase().substringBefore('-') == language }
            .minByOrNull { it.key }
            ?.value
    }

    /**
     * Keeps only the languages the app can display, plus [alwaysKeep].
     *
     * Used during sync: the F-Droid index carries around 30 languages per description, and
     * keeping them all would multiply the database size without any of the extras ever being
     * able to appear on screen.
     *
     * If pruning would empty the map — an app translated *only* into Japanese — the first
     * original entry is kept: a field with no language at all would be a regression, not a
     * saving.
     */
    fun prunedToDisplayable(alwaysKeep: Set<String> = DISPLAYABLE_TAGS): LocalizedText {
        if (byTag.isEmpty()) return this
        val kept = byTag.filterKeys { tag ->
            val lower = tag.lowercase()
            lower in alwaysKeep || lower.substringBefore('-') in alwaysKeep
        }
        if (kept.isNotEmpty()) return LocalizedText(kept)
        val first = byTag.entries.minByOrNull { it.key } ?: return this
        return LocalizedText(mapOf(first.key to first.value))
    }

    companion object {
        val EMPTY: LocalizedText = LocalizedText(emptyMap())

        /**
         * The languages worth keeping: the 5 of the interface.
         *
         * Derived from [SupportedLanguage] rather than being a separate list, so adding a
         * language to the app automatically extends what sync keeps. Regional variants
         * (`en-US`, `de-DE`) pass through the language-subtag comparison.
         */
        val DISPLAYABLE_TAGS: Set<String> = SupportedLanguage.entries.map { it.tag }.toSet()

        fun of(single: String?): LocalizedText =
            if (single.isNullOrEmpty()) EMPTY else LocalizedText(mapOf(SupportedLanguage.FALLBACK.tag to single))
    }
}
