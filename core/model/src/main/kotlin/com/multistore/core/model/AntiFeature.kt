package com.multistore.core.model

/**
 * A warning the store attaches to a **version** (advertising, trackers, non-free dependencies,
 * deprecated algorithms…).
 *
 * The name comes from F-Droid's vocabulary, the only one of the nine stores to publish them in
 * structured form. Two measured facts about the real index:
 *
 *  - `metadata.antiFeatures` **does not exist**: 0 occurrences across 4,257 packages.
 *    Anti-features live only inside `versions.<sha>.antiFeatures` (2,666 occurrences). Reading
 *    them at package level means never showing them;
 *  - the repository publishes name and description **already localised** into ~100 languages.
 *
 * Hence [name] and [description] are [LocalizedText] and not `strings.xml` keys: they are data
 * arriving over the network, not interface text, and they stay correct when F-Droid adds one we
 * do not know about.
 */
data class AntiFeature(
    /** The store's identifier, e.g. `Tracking`, `NonFreeNet`. Never translated. */
    val id: String,
    val name: LocalizedText = LocalizedText.EMPTY,
    val description: LocalizedText = LocalizedText.EMPTY,
) {
    /** The name to show: the localised one if present, otherwise the raw id. */
    fun displayName(preferredTags: List<String>): String = name.resolve(preferredTags) ?: id
}

/**
 * A store category, with the name already localised by the store itself.
 *
 * [appCount] does not come from the store: F-Droid publishes **108** categories and declares an
 * app count for none of them. Sync counts them, and the count is what orders them —
 * alphabetically the first three are "AI Chat", "Action Game" and "Alarm Clock", while "System"
 * (629 apps) and "Internet" (614) land mid-list. With 108 entries any interface shows a subset,
 * and an alphabetical subset is not a useful one.
 */
data class Category(
    val id: String,
    val name: LocalizedText = LocalizedText.EMPTY,
    val appCount: Int = 0,
) {
    fun displayName(preferredTags: List<String>): String = name.resolve(preferredTags) ?: id
}
