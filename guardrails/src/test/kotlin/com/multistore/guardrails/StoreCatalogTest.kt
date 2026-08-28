package com.multistore.guardrails

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every **implemented** store has a name in the catalogue, a translated description and a row in the
 * store table.
 *
 * The "add a new store" checklist used to ask, at point 6, for an entry in `settings.proto`
 * (`StoreSettings`), covered by `SettingsCoverageTest`. That route was abandoned for a substantive
 * reason: per-store enablement already lives in Room — the `enabled` column of the `stores` table,
 * next to the order and the circuit-breaker state — and that is what `SearchRepository` reads.
 * Duplicating it in the DataStore would have given two divergeable values, with search reading one and
 * Settings the other.
 *
 * What point 6 really protected — **no store without its five translations** — is protected by this
 * guardrail, and more tightly: a proto field would have guaranteed an entry existed, not that the
 * entry talked about the right store.
 *
 * The list is derived, not declared. The stores to check are those whose `:store:<name>` module **has
 * sources**. Not `StoreId.entries`, and not a list written here, which whoever adds an adapter has no
 * reason to come and update. The day a store module stops being empty, this test starts asking for its
 * description on its own.
 */
@DisplayName("Stores: every implemented adapter has a name, a description in 5 languages and a documented row")
class StoreCatalogTest {

    @Test
    fun `every implemented store has a description in all five languages`() {
        val stores = implementedStores()
        assertTrue(stores.isNotEmpty()) {
            "No `:store:<name>` module with sources found: the detection is broken, not the " +
                "repository."
        }

        val missing = mutableListOf<String>()
        for (store in stores) {
            val key = descriptionKey(store)
            for (qualifier in LANGUAGE_QUALIFIERS) {
                if (!hasString(qualifier, key)) {
                    missing += "$qualifier: manca <string name=\"$key\">"
                }
            }
        }

        assertTrue(missing.isEmpty()) {
            buildString {
                appendLine("Stores with no translated description:")
                missing.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Implemented stores: ${stores.joinToString { it.wireName }}")
                appendLine(
                    "The description is the only place where the user reads what to expect from " +
                        "that source — whether it publishes hashes, whether the package is " +
                        "verifiable. In an app that installs APKs it is not decorative text.",
                )
            }
        }
    }

    @Test
    fun `every implemented store is resolved by the description registry`() {
        val registry = File(RepoLayout.root, DESCRIPTION_REGISTRY)
        assertTrue(registry.isFile) { "Description registry missing: $DESCRIPTION_REGISTRY" }
        val text = registry.readText()

        val unmapped = implementedStores().filter { store ->
            // The function maps `StoreId.X -> R.string.…`: we look for the enum constant followed by an
            // arrow, not for the name alone, so an entry in the "not yet implemented" branch does not
            // count as mapped.
            !Regex("""StoreId\.${store.constant}\s*->\s*R\.string\.""").containsMatchIn(text)
        }

        assertTrue(unmapped.isEmpty()) {
            "These stores have an adapter but no description in `storeDescriptionRes`: " +
                unmapped.joinToString { it.wireName } +
                ". The `when` is exhaustive, so they compile: they sit in the not-yet-implemented " +
                "branch and their Settings row shows only the host."
        }
    }

    @Test
    fun `every implemented store has a row in the store table`() {
        val reference = File(RepoLayout.root, "REFERENCE.md")
        assertTrue(reference.isFile) { "REFERENCE.md is missing" }
        val text = reference.readText()

        val missing = implementedStores().filterNot { store ->
            // The table writes the store in bold in the first column: `| **apkcombo** |`.
            text.contains("**${store.wireName}**")
        }

        assertTrue(missing.isEmpty()) {
            "Stores with no row in REFERENCE.md's table: ${missing.joinToString { it.wireName }}. " +
                "That table is the only place where host, risk, download mode and measured " +
                "pitfalls sit together: an adapter that is not there is an adapter the next " +
                "reader does not know the behaviour of."
        }
    }

    /**
     * Every implemented store receives its configuration **through** the remote override.
     *
     * It is the remote-config exit criterion made verifiable: "a change of selector or of domain is
     * repaired by publishing `parsers.json`, with no app release". That only holds if every adapter
     * goes through there; a `@Provides` returning the bare compiled configuration produces a store that
     * **cannot be repaired**, and no compiler would say so — the type is the same and the code works
     * perfectly, until that store changes its markup.
     *
     * The guardrail is here and not among `:app`'s tests for the same reason as the other three in this
     * class: the store list is **derived** from the modules that have sources, so the next adapter is
     * demanded on its own, without anybody having to remember to add it.
     */
    @Test
    fun `every implemented store receives its configuration through the remote override`() {
        val module = File(RepoLayout.root, STORE_MODULE)
        assertTrue(module.isFile) { "Store Hilt module missing: $STORE_MODULE" }
        val text = module.readText()

        val unwired = implementedStores().filterNot { store ->
            Regex("""\.override\(\s*StoreId\.${store.constant}\s*,""").containsMatchIn(text)
        }

        assertTrue(unwired.isEmpty()) {
            buildString {
                appendLine(
                    "These stores receive their compiled configuration without going through " +
                        "remote config: " + unwired.joinToString { it.wireName },
                )
                appendLine()
                appendLine(
                    "In `$STORE_MODULE` the `@Provides` must be " +
                        "`parsers.override(StoreId.X, XConfig())`, not `XConfig()`. " +
                        "Without it, a change of selector or of domain on that store requires an " +
                        "app release — which is exactly what remote config exists to avoid.",
                )
            }
        }
    }

    // --- detection ----------------------------------------------------------------------------

    private data class Store(val directory: String, val wireName: String, val constant: String)

    /**
     * The stores with a real adapter: a `:store:<name>` module with at least one `.kt` in `src/main`.
     *
     * `:store:api` and `:store:common` are not stores and are excluded by name — they are the only two
     * modules under `store/` that do not correspond to a `StoreId` constant, and indeed the link with
     * the enum is precisely the filter: a folder with no matching constant is not a store.
     */
    private fun implementedStores(): List<Store> {
        val byDirectory = storeIds().associateBy { it.wireName.replace("-", "") }
        return File(RepoLayout.root, "store").listFiles()
            ?.filter { it.isDirectory && hasProductionSources(it) }
            ?.mapNotNull { directory -> byDirectory[directory.name] }
            ?.sortedBy { it.wireName }
            .orEmpty()
    }

    private fun hasProductionSources(module: File): Boolean =
        File(module, "src/main").walkTopDown().any { it.isFile && it.extension == "kt" }

    /** The `StoreId` constants, read from the source: `FDROID("f-droid"),`. */
    private fun storeIds(): List<Store> {
        val source = File(RepoLayout.root, STORE_ID_SOURCE)
        assertTrue(source.isFile) { "StoreId source missing: $STORE_ID_SOURCE" }
        return STORE_ID_ENTRY.findAll(source.readText())
            .map { Store(directory = "", constant = it.groupValues[1], wireName = it.groupValues[2]) }
            .toList()
            .also { assertTrue(it.isNotEmpty()) { "No constant found in $STORE_ID_SOURCE" } }
    }

    private fun descriptionKey(store: Store): String =
        "settings_store_${store.wireName.replace("-", "_")}_description"

    private fun hasString(qualifier: String, key: String): Boolean {
        val directory = File(RepoLayout.root, "$SETTINGS_RES/$qualifier")
        return RepoLayout.resourceFiles(directory.parentFile, qualifier).any { file ->
            RepoLayout.parseXml(file).getElementsByTagName("string").let { nodes ->
                (0 until nodes.length).any { index ->
                    (nodes.item(index) as? org.w3c.dom.Element)?.getAttribute("name") == key
                }
            }
        }
    }

    private companion object {
        const val STORE_ID_SOURCE = "core/model/src/main/kotlin/com/multistore/core/model/StoreId.kt"
        const val DESCRIPTION_REGISTRY =
            "feature/settings/src/main/kotlin/com/multistore/feature/settings/SettingsRegistry.kt"
        const val SETTINGS_RES = "feature/settings/src/main/res"
        const val STORE_MODULE = "app/src/main/kotlin/com/multistore/app/di/StoreModule.kt"

        /** `FDROID("f-droid"),` */
        val STORE_ID_ENTRY = Regex("""^\s{4}([A-Z][A-Z0-9_]*)\("([a-z0-9-]+)"\),""", RegexOption.MULTILINE)

        /** The five languages required by rule 1. */
        val LANGUAGE_QUALIFIERS = listOf("values", "values-it", "values-fr", "values-es", "values-de")
    }
}
