package com.multistore.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.model.StoreId
import java.text.Normalizer
import java.util.Locale

/**
 * Searching **inside** Settings.
 *
 * The reason is arithmetic: there are twenty-eight entries spread over ten sections. Past a certain
 * length a settings screen is not read, it is scoured.
 *
 * An entry's text is the **registry's**, not the one written in the row: that is why the rows stopped
 * carrying their own strings (see the note on [SETTINGS_REGISTRY]). With two sources, searching for
 * "dark" could have hidden a row that visibly contains that very word, and no test would have said
 * so.
 *
 * Label and description are compared **together**, not just the label. The description is often the
 * only place the word the user has in mind appears: the entry is called "Allow unverified hash", and
 * whoever searches for "SHA-256" finds it only because that token is in the description.
 *
 * The text and the query are both reduced to lowercase, without diacritics, with punctuation turned
 * into a space: so "perché" and "perche" are the same thing, and "F-Droid" becomes "f droid". On its
 * own that form does not find "fdroid" typed as one word, which is the most likely way somebody
 * writes that name. Hence the second form, the same one without spaces, and a term matching **either
 * of the two** is enough.
 *
 * A multi-word query requires all of them to be present. With "at least one", typing "metered
 * network" would show every entry containing "a" — that is, all of them.
 */
internal object SettingsSearch {

    private val DIACRITIC = Regex("""\p{Mn}+""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")

    /** Lowercase, without diacritics, punctuation reduced to a space. */
    fun normalize(raw: String): String {
        val lowered = raw.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val stripped = DIACRITIC.replace(decomposed, "")
        return NON_ALNUM.replace(stripped, " ").trim()
    }

    /** The typed terms, already normalised. Empty = no filter. */
    fun terms(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }

    /**
     * `true` if **every** term appears in the text, with or without spaces.
     *
     * An empty `terms` answers `true`: that is the "no search in progress" case, and handling it
     * here saves every caller from having to remember to check for it.
     */
    fun matches(terms: List<String>, vararg text: String): Boolean {
        if (terms.isEmpty()) return true
        val spaced = text.joinToString(" ") { normalize(it) }
        val squashed = spaced.replace(" ", "")
        return terms.all { it in spaced || it in squashed }
    }
}

/**
 * What the screen must show, given a query.
 *
 * A value rather than a function called row by row: the decision "does this section still have
 * anything in it?" has to be taken **before** drawing the heading, and a section with a title and
 * nothing under it is the quickest way to make a search that worked look broken.
 */
internal data class SettingsFilter(
    val query: String,
    private val keys: Set<SettingKey>,
    private val actions: Set<SettingsActionKey>,
    private val stores: Set<StoreId>,
    private val sections: Set<SettingsSection>,
) {
    val active: Boolean get() = query.isNotBlank()

    /** A search is running and there is nothing to show: the screen says so instead of going blank. */
    val nothingFound: Boolean
        get() = active && keys.isEmpty() && actions.isEmpty() && stores.isEmpty()

    fun shows(key: SettingKey): Boolean = !active || key in keys

    fun shows(key: SettingsActionKey): Boolean = !active || key in actions

    fun shows(storeId: StoreId): Boolean = !active || storeId in stores

    fun shows(section: SettingsSection): Boolean = !active || section in sections

    companion object {
        val NONE = SettingsFilter("", emptySet(), emptySet(), emptySet(), emptySet())

        /**
         * Builds the filter, resolving the strings.
         *
         * [resolve] rather than calling `stringResource` in here, because this function has to stay
         * testable on the JVM: it is the single place that decides what disappears from the screen,
         * and exactly the kind of logic that, when wrong, hides a setting without anybody noticing.
         */
        fun of(
            query: String,
            storeEntries: List<StoreEntry>,
            resolve: (Int) -> String,
        ): SettingsFilter {
            val terms = SettingsSearch.terms(query)
            if (terms.isEmpty()) return NONE

            // A section whose **title** matches shows everything it contains: whoever searches for
            // "security" wants the section, not only the entries repeating that word.
            val matchedSections = SettingsSection.entries
                .filterTo(mutableSetOf()) { SettingsSearch.matches(terms, resolve(it.titleRes)) }

            val keys = SETTINGS_REGISTRY.filterTo(mutableSetOf()) { entry ->
                entry.section in matchedSections || SettingsSearch.matches(
                    terms,
                    resolve(entry.labelRes),
                    resolve(entry.descriptionRes),
                )
            }.mapTo(mutableSetOf()) { it.key }

            val actions = SETTINGS_ACTIONS.filterTo(mutableSetOf()) { action ->
                action.section in matchedSections || SettingsSearch.matches(
                    terms,
                    resolve(action.labelRes),
                    resolve(action.descriptionRes),
                    resolve(action.actionRes),
                )
            }.mapTo(mutableSetOf()) { it.key }

            // Stores are not registry entries — their enablement lives in Room — but they are rows
            // all the same, and searching for "apkmirror" must find them. The host is compared too:
            // that is what tells `apkmody.mobi` apart from the domain on the blocklist.
            val stores = storeEntries.filterTo(mutableSetOf()) { entry ->
                SettingsSection.STORES in matchedSections || SettingsSearch.matches(
                    terms,
                    entry.displayName,
                    entry.host,
                    storeDescriptionRes(entry.storeId)?.let(resolve).orEmpty(),
                )
            }.mapTo(mutableSetOf()) { it.storeId }

            val sections = buildSet {
                addAll(SETTINGS_REGISTRY.filter { it.key in keys }.map { it.section })
                addAll(SETTINGS_ACTIONS.filter { it.key in actions }.map { it.section })
                if (stores.isNotEmpty()) add(SettingsSection.STORES)
            }

            return SettingsFilter(query, keys, actions, stores, sections)
        }
    }
}

/**
 * The filter for the current composition.
 *
 * `stringResource` rather than `context.getString`: it resolves through `LocalResources`, so it
 * follows the per-app locale set by `AppCompatDelegate` without anybody having to remember. The cost
 * is one resolution per string on each query change — some forty lookups on already-loaded resources.
 */
@Composable
internal fun rememberSettingsFilter(query: String, stores: List<StoreEntry>): SettingsFilter {
    val labels = SETTINGS_REGISTRY.associate { it.labelRes to stringResource(it.labelRes) } +
        SETTINGS_REGISTRY.associate { it.descriptionRes to stringResource(it.descriptionRes) } +
        SETTINGS_ACTIONS.associate { it.labelRes to stringResource(it.labelRes) } +
        SETTINGS_ACTIONS.associate { it.descriptionRes to stringResource(it.descriptionRes) } +
        SETTINGS_ACTIONS.associate { it.actionRes to stringResource(it.actionRes) } +
        SettingsSection.entries.associate { it.titleRes to stringResource(it.titleRes) } +
        StoreId.entries.mapNotNull { storeDescriptionRes(it) }
            .associateWith { stringResource(it) }

    return remember(query, stores, labels) {
        SettingsFilter.of(query, stores) { id -> labels.getValue(id) }
    }
}
