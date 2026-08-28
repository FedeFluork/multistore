package com.multistore.feature.settings

import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.net.StoreHealth
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.model.StoreId
import org.junit.Test

/**
 * Search inside Settings, tested where the logic is: on the JVM, without Compose.
 *
 * The strings here are **fake**, on purpose. If the test compared the real translations it would be
 * measuring word choice rather than the filter: renaming "Theme" to "Appearance" would turn it red
 * without anything having broken. What has to stay true is the mechanics — all terms, a section
 * pulling its entries along, stores that are not registry entries but are rows all the same.
 *
 * The only thing tested with realistic text is normalisation, because there the text **is** the
 * subject: "perche" must find "perché", and "fdroid" must find "F-Droid".
 */
class SettingsSearchTest {

    /** A distinct text per resource, so that a match cannot be accidental. */
    private val texts: Map<Int, String> = buildMap {
        SETTINGS_REGISTRY.forEach { entry ->
            put(entry.labelRes, "label ${entry.key.name}")
            put(entry.descriptionRes, "explanation ${entry.key.name}")
        }
        SETTINGS_ACTIONS.forEach { action ->
            put(action.labelRes, "label ${action.key.name}")
            put(action.descriptionRes, "explanation ${action.key.name}")
            put(action.actionRes, "button ${action.key.name}")
        }
        SettingsSection.entries.forEach { put(it.titleRes, "section ${it.name}") }
        StoreId.entries.forEach { storeId ->
            storeDescriptionRes(storeId)?.let { put(it, "description ${storeId.wireName}") }
        }
    }

    private val stores = listOf(
        entry(StoreId.FDROID, "F-Droid", "f-droid.org"),
        entry(StoreId.APKMIRROR, "APKMirror", "www.apkmirror.com"),
        entry(StoreId.APKMODY, "APKMody", "apkmody.mobi"),
    )

    private fun entry(storeId: StoreId, name: String, host: String) = StoreEntry(
        storeId = storeId,
        displayName = name,
        host = host,
        enabled = true,
        health = StoreHealth(storeId),
    )

    private fun filter(query: String) = SettingsFilter.of(query, stores) { texts.getValue(it) }

    @Test
    fun `with no query nothing is filtered`() {
        val filter = filter("   ")

        assertThat(filter.active).isFalse()
        assertThat(filter.nothingFound).isFalse()
        // Every key, every action, every section and every store: "no search" is not a special case
        // each caller has to remember to handle, it is the default answer.
        assertThat(SettingKey.entries.all(filter::shows)).isTrue()
        assertThat(SettingsActionKey.entries.all(filter::shows)).isTrue()
        assertThat(SettingsSection.entries.all(filter::shows)).isTrue()
        assertThat(StoreId.entries.all(filter::shows)).isTrue()
    }

    @Test
    fun `an entry's label finds it, and only it`() {
        val filter = filter("label THEME_MODE")

        assertThat(filter.shows(SettingKey.THEME_MODE)).isTrue()
        assertThat(filter.shows(SettingKey.DYNAMIC_COLOR)).isFalse()
        assertThat(filter.shows(SettingsSection.APPEARANCE)).isTrue()
        assertThat(filter.shows(SettingsSection.SECURITY)).isFalse()
    }

    @Test
    fun `the description is searched too, not only the title`() {
        // This is the case that makes search useful: the word the user has in mind is nearly always
        // in the explanation, not in the entry's name.
        val filter = filter("explanation ALLOW_SIGNER_MISMATCH")

        assertThat(filter.shows(SettingKey.ALLOW_SIGNER_MISMATCH)).isTrue()
        assertThat(filter.shows(SettingKey.ALLOW_UNVERIFIED_HASH)).isFalse()
    }

    @Test
    fun `a section title pulls all its entries along`() {
        val filter = filter("section SECURITY")

        assertThat(filter.shows(SettingKey.ALLOW_UNVERIFIED_HASH)).isTrue()
        assertThat(filter.shows(SettingKey.ALLOW_SIGNER_MISMATCH)).isTrue()
        assertThat(filter.shows(SettingKey.THEME_MODE)).isFalse()
    }

    @Test
    fun `every term is required, not at least one`() {
        // With "at least one" this query would show every entry containing "label", that is all of
        // them, and search would stop filtering exactly when the user is being more precise.
        val filter = filter("label THEME_MODE DYNAMIC_COLOR")

        assertThat(filter.nothingFound).isTrue()
    }

    @Test
    fun `stores are rows even though they are not registry entries`() {
        val filter = filter("APKMirror")

        assertThat(filter.shows(StoreId.APKMIRROR)).isTrue()
        assertThat(filter.shows(StoreId.FDROID)).isFalse()
        assertThat(filter.shows(SettingsSection.STORES)).isTrue()
        assertThat(filter.nothingFound).isFalse()
    }

    @Test
    fun `a store's host can be searched for`() {
        // `apkmody.mobi` is the host that works; `apkmody.com` is on the blocklist. Whoever read the
        // note and searches for the domain must find the row.
        val filter = filter("apkmody.mobi")

        assertThat(filter.shows(StoreId.APKMODY)).isTrue()
        assertThat(filter.shows(StoreId.APKMIRROR)).isFalse()
    }

    @Test
    fun `a query that finds nothing says so`() {
        val filter = filter("thiswordexistsnowhereatall")

        assertThat(filter.active).isTrue()
        assertThat(filter.nothingFound).isTrue()
        assertThat(filter.shows(SettingKey.THEME_MODE)).isFalse()
        assertThat(filter.shows(SettingsSection.APPEARANCE)).isFalse()
    }

    @Test
    fun `diacritics do not count`() {
        // Whoever searches on a keyboard without accents must find the same: that is the normal case
        // in Italian, French and Spanish, three of the five supported languages.
        assertThat(SettingsSearch.matches(SettingsSearch.terms("cafe"), "café")).isTrue()
        assertThat(SettingsSearch.matches(SettingsSearch.terms("CAFÉ"), "cafe")).isTrue()
        assertThat(SettingsSearch.matches(SettingsSearch.terms("uber"), "über")).isTrue()
    }

    @Test
    fun `punctuation does not separate when the user does not type it`() {
        val terms = SettingsSearch.terms("fdroid")

        // "F-Droid" normalised becomes "f droid", which does not contain "fdroid": without the second
        // form — the same one without spaces — this search would find nothing, and it is the most
        // likely way somebody writes that name.
        assertThat(SettingsSearch.matches(terms, "F-Droid")).isTrue()
        // And the reverse must hold too: typed punctuation must not exclude.
        assertThat(SettingsSearch.matches(SettingsSearch.terms("f-droid"), "F-Droid")).isTrue()
    }
}
