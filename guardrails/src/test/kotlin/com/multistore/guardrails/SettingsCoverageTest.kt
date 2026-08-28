package com.multistore.guardrails

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guardrail #3, rule 2: **every configurable feature has an entry in Settings**.
 *
 * The test compares the fields of `core/datastore/src/main/proto/settings.proto` with the entries of
 * `SETTINGS_REGISTRY` in `:feature:settings` and fails in both directions:
 *
 *  - a proto field with no UI entry → the user cannot change it, but the app reads it: that is hidden
 *    state, the sort of thing one only discovers through a bug report;
 *  - a UI entry pointing at a non-existent field → a leftover from a rename that would write into the
 *    void at runtime.
 *
 * The link goes through the snake_case field names, read from both sides as text: this way the test
 * has to neither compile the proto nor instantiate the UI, and stays a JVM test of a few milliseconds.
 */
@DisplayName("Settings coverage: settings.proto vs the UI registry")
class SettingsCoverageTest {

    private companion object {
        val PROTO_FILE = "core/datastore/src/main/proto/settings.proto"
        val REGISTRY_FILE =
            "feature/settings/src/main/kotlin/com/multistore/feature/settings/SettingsRegistry.kt"

        /**
         * A proto3 message field: `<type> <snake_case_name> = <number>;`
         * It also catches `repeated`, `optional` and qualified types (`map<string, X>`).
         */
        val PROTO_FIELD = Regex(
            """^\s*(?:repeated\s+|optional\s+)?[A-Za-z_][\w.<>, ]*\s+([a-z][a-z0-9_]*)\s*=\s*\d+\s*;""",
            RegexOption.MULTILINE,
        )

        /** One entry of the SettingKey enum: `NAME("proto_field"),` */
        val REGISTRY_ENTRY = Regex("""^\s*[A-Z][A-Z0-9_]*\("([a-z][a-z0-9_]*)"\)""", RegexOption.MULTILINE)

        /** Comment lines: the fields named there are documentation, not declarations. */
        val COMMENT_LINE = Regex("""^\s*//.*$""", RegexOption.MULTILINE)
    }

    @Test
    @DisplayName("every settings.proto field has an entry in the Settings screen")
    fun everyProtoFieldHasASettingsEntry() {
        val protoFile = File(RepoLayout.root, PROTO_FILE)
        val registryFile = File(RepoLayout.root, REGISTRY_FILE)

        assertTrue(
            protoFile.isFile,
            "$PROTO_FILE does not exist. If the file has been moved, update this test: a " +
                "guardrail that cannot find what it must check is worse than no guardrail, " +
                "because it passes in silence.",
        )
        assertTrue(
            registryFile.isFile,
            "$REGISTRY_FILE does not exist. The Settings entry registry is the UI half of " +
                "rule 2.",
        )

        val protoFields = protoFile.readText()
            .let { COMMENT_LINE.replace(it, "") }
            .let { stripBlockComments(it) }
            .let { text -> PROTO_FIELD.findAll(text).map { it.groupValues[1] }.toSet() }

        val registryFields = registryFile.readText()
            .let { COMMENT_LINE.replace(it, "") }
            .let { text -> REGISTRY_ENTRY.findAll(text).map { it.groupValues[1] }.toSet() }

        assertTrue(
            protoFields.isNotEmpty(),
            "No field found in $PROTO_FILE: the regex no longer recognises the schema, so " +
                "this test would be passing vacuously.",
        )

        val uncovered = (protoFields - registryFields).sorted()
        val dangling = (registryFields - protoFields).sorted()

        assertTrue(
            uncovered.isEmpty() && dangling.isEmpty(),
            buildString {
                appendLine()
                appendLine("Settings coverage violated (rule 2).")
                appendLine()
                if (uncovered.isNotEmpty()) {
                    appendLine("Fields in settings.proto with no entry in Settings:")
                    uncovered.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("  Add a SettingsEntry to SETTINGS_REGISTRY, with label and")
                    appendLine("  description translated into all 5 languages. If the field is not")
                    appendLine("  really user-configurable, it does not belong in settings.proto.")
                    appendLine()
                }
                if (dangling.isNotEmpty()) {
                    appendLine("Entries in SETTINGS_REGISTRY pointing at a non-existent field:")
                    dangling.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("  Likely a proto field rename without updating the registry.")
                }
                appendLine("Proto fields: ${protoFields.sorted()}")
                appendLine("Registry entries: ${registryFields.sorted()}")
            },
        )
    }

    /** Strips C-style comments, which in the proto can contain field examples. */
    private fun stripBlockComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
}
