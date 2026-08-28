package com.multistore.guardrails

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The eighth guardrail, and it closes the third side of a triangle that had only two.
 *
 * `SettingsCoverageTest` verifies **proto ↔ registry**: no field without an entry, no entry without a
 * field. What nobody verified is **registry ↔ screen**: a `SettingsEntry` could exist, have its five
 * translations, pass the guardrail — and be drawn nowhere. The field would exist in the DataStore,
 * somebody would read it, and the user would have no way of changing it. That is exactly the hidden
 * state rule 2 forbids, reached through the one side nobody was watching.
 *
 * It becomes easier to produce as the entries grow: adding a row to the registry takes three seconds,
 * drawing it does not.
 *
 * Three checks and not one, because they are three different holes and each lets through something
 * the other two do not see:
 *
 *  - **a key with no row** — the setting exists and cannot be touched;
 *  - **an action with no row** — the button is not there, and unlike a setting it does not even leave
 *    a trace in the DataStore that might arouse suspicion;
 *  - **a section with no row** — the heading is never drawn, so the entries declaring it stay invisible
 *    even if their rows are written. It is the most insidious of the three, because the first two
 *    checks stay green.
 *
 * By text and not by reflection, on the same criterion as `SettingsCoverageTest`: reading the sources
 * keeps this test on the JVM, in milliseconds, without instantiating Compose or running Hilt.
 *
 * It looks for the **position**, not the mention — and that was decided by an injection. The first
 * draft looked for the token's mere presence: `"SettingKey.SEARCH_TIMEOUT" in screen`. Removing an
 * entry's row left the test **green**, because that same key also appears in its section's
 * `filter.rowsOf(...)` list — that is, in a place that declares "this entry exists in this section"
 * and not "this entry is drawn".
 *
 * So it looks for the form that **only a real row** produces:
 *
 *  - `key = SettingKey.X,` — a row's argument;
 *  - `actionOf(SettingsActionKey.X)` — a button that takes its strings here, or
 *    `action = SettingsActionKey.X,` — the argument of a row that takes them itself. Both forms exist:
 *    the four storage-level rows receive the action as a parameter and call `actionOf` **inside** the
 *    shared composable, which is the same structure as `key = SettingKey.X,` for settings. Accepting
 *    the second form does not widen the criterion: it stays a **position** — the argument of a call
 *    that draws — and not a mention, and indeed removing one of those four rows makes the test fail;
 *  - `SettingsSection.X.titleRes` — the heading drawn.
 *
 * It is the difference between a test and a caption, and it took three injections to see.
 */
@DisplayName("Screen coverage: registry vs SettingsScreen")
class SettingsScreenCoverageTest {

    private companion object {
        const val REGISTRY_FILE =
            "feature/settings/src/main/kotlin/com/multistore/feature/settings/SettingsRegistry.kt"
        const val SCREEN_FILE =
            "feature/settings/src/main/kotlin/com/multistore/feature/settings/SettingsScreen.kt"

        /** `THEME_MODE("theme_mode"),` — one entry of the key enum. */
        val SETTING_KEY = Regex("""^\s*([A-Z][A-Z0-9_]*)\("[a-z][a-z0-9_]*"\)""", RegexOption.MULTILINE)

        /** `RECLAIM_SPACE,` inside `enum class SettingsActionKey`: no parentheses, by construction. */
        val ACTION_KEY = Regex("""^\s{4}([A-Z][A-Z0-9_]*),\s*$""", RegexOption.MULTILINE)

        /** `APPEARANCE(R.string.settings_section_appearance),` */
        val SECTION = Regex("""^\s*([A-Z][A-Z0-9_]*)\(R\.string\.[a-z_]+\),""", RegexOption.MULTILINE)

        val COMMENT_LINE = Regex("""^\s*(//|\*|/\*).*$""", RegexOption.MULTILINE)
    }

    @Test
    @DisplayName("every registry key, action and section is drawn by the screen")
    fun everyRegistryEntryIsRendered() {
        val registryFile = File(RepoLayout.root, REGISTRY_FILE)
        val screenFile = File(RepoLayout.root, SCREEN_FILE)

        assertTrue(
            registryFile.isFile && screenFile.isFile,
            "One of the two files does not exist: $REGISTRY_FILE, $SCREEN_FILE. If they have " +
                "been moved, update this test — a guardrail that cannot find what it must check " +
                "passes in silence, which is worse than not having it.",
        )

        val registry = registryFile.readText()
        // The screen is read **without comments**: a key named only in an explanatory comment is not a
        // drawn row, and counting it would turn this test into a caption.
        val screen = COMMENT_LINE.replace(screenFile.readText(), "")

        val enumBody = { name: String ->
            val start = registry.indexOf("enum class $name")
            require(start >= 0) { "enum $name not found in $REGISTRY_FILE" }
            registry.substring(start, registry.indexOf("\n}", start))
        }

        val keys = SETTING_KEY.findAll(enumBody("SettingKey")).map { it.groupValues[1] }.toSet()
        val actions = ACTION_KEY.findAll(enumBody("SettingsActionKey")).map { it.groupValues[1] }.toSet()
        val sections = SECTION.findAll(enumBody("SettingsSection")).map { it.groupValues[1] }.toSet()

        assertTrue(
            keys.isNotEmpty() && actions.isNotEmpty() && sections.isNotEmpty(),
            "The regexes no longer recognise $REGISTRY_FILE's enums: keys=$keys, " +
                "actions=$actions, sections=$sections. This test would be passing vacuously.",
        )

        val missingKeys = keys.filterNot { "key = SettingKey.$it," in screen }.sorted()
        val missingActions = actions
            .filterNot { "actionOf(SettingsActionKey.$it)" in screen || "action = SettingsActionKey.$it," in screen }
            .sorted()
        val missingSections = sections.filterNot { "SettingsSection.$it.titleRes" in screen }.sorted()

        assertTrue(
            missingKeys.isEmpty() && missingActions.isEmpty() && missingSections.isEmpty(),
            buildString {
                appendLine()
                appendLine("The registry declares something the screen does not draw.")
                appendLine()
                if (missingKeys.isNotEmpty()) {
                    appendLine("Keys with no row in SettingsScreen.kt:")
                    missingKeys.forEach { appendLine("  - SettingKey.$it") }
                    appendLine("  The proto field exists, somebody reads it, and the user cannot")
                    appendLine("  change it. A row with `key = SettingKey.<name>,` is required:")
                    appendLine("  naming it in `rowsOf(...)` is not enough and must not be.")
                    appendLine()
                }
                if (missingActions.isNotEmpty()) {
                    appendLine("Actions with no button in SettingsScreen.kt:")
                    missingActions.forEach { appendLine("  - SettingsActionKey.$it") }
                    appendLine()
                }
                if (missingSections.isNotEmpty()) {
                    appendLine("Sections whose heading is never drawn:")
                    missingSections.forEach { appendLine("  - SettingsSection.$it.titleRes") }
                    appendLine("  The entries that declare it stay invisible even if their rows")
                    appendLine("  exist, and the other two checks stay green.")
                }
            },
        )
    }
}
