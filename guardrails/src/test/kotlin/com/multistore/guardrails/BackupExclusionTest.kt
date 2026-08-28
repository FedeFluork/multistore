package com.multistore.guardrails

import com.multistore.guardrails.RepoLayout.elements
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every private folder the app creates under `filesDir` must be excluded from backup — from **all
 * three** rule sets.
 *
 * This guardrail comes from a real defect, and the shape of the defect is why it is a test and not a
 * comment: `backup_rules.xml` declared in words that "the backup excludes the settings DataStore
 * **and the APK staging cache**", and in the body of the file the staging exclusion was not there.
 * With `android:allowBackup="true"`, an APK downloaded from a third-party source and not yet through
 * pre-install verification was eligible for the user's cloud backup and for transfer to a second
 * device.
 *
 * The list is derived rather than declared because a hand-written list in the test would have the
 * same defect as the comment it replaced: whoever adds a new folder has no reason to come here and
 * update it. The folders are therefore found in the code, by looking for `File(filesDir, …)` in the
 * production sources, and the constant is resolved in the same file. **If one cannot be resolved, the
 * test fails** instead of passing silently: that is a new folder under `filesDir` nobody can
 * classify, which is exactly the case to stop.
 *
 * All three sets matter. `full-backup-content` applies up to Android 11; from Android 12
 * `data-extraction-rules` takes over, and it splits into `cloud-backup` (backup to Google) and
 * `device-transfer` (the direct copy to a new phone). Excluding two out of three leaves a route open,
 * and they are three files of differing syntax that no compiler compares with one another.
 */
@DisplayName("Backup: every private folder under filesDir is excluded by every rule set")
class BackupExclusionTest {

    @Test
    fun `every folder created under filesDir is excluded from all three rule sets`() {
        val required = filesDirDirectories()
        assertTrue(required.isNotEmpty()) {
            "No folder under `filesDir` found in the sources: the detection is broken, " +
                "not the repository. It was looking for `File(<context>.filesDir, \"name\")` or a constant."
        }

        val missing = mutableListOf<String>()
        for (ruleSet in RULE_SETS) {
            val excluded = excludedFilePaths(ruleSet)
            for (dir in required) {
                if (excluded.none { it.trimEnd('/') == dir }) {
                    missing += "${ruleSet.describe()}: missing <exclude domain=\"file\" path=\"$dir/\" />"
                }
            }
        }

        assertTrue(missing.isEmpty()) {
            buildString {
                appendLine("Private folders under `filesDir` not excluded from backup:")
                missing.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Folders found in the sources: ${required.joinToString()}")
                appendLine(
                    "If one of these really can travel through a backup, the exclusion must be " +
                        "removed together with a line of justification: what ends up in here is " +
                        "unverified APKs and device-bound settings.",
                )
            }
        }
    }

    @Test
    fun `the database does not travel through a backup`() {
        val missing = RULE_SETS.filter { ruleSet ->
            excluded(ruleSet, domain = "database").none { it == DATABASE_NAME }
        }
        assertTrue(missing.isEmpty()) {
            "The database is not excluded by: ${missing.joinToString { it.describe() }}. " +
                "It is rebuildable from a sync and weighs tens of MB."
        }
    }

    // --- detection --------------------------------------------------------------------------

    /**
     * The top-level directories the production sources create under `filesDir`.
     *
     * `File(context.filesDir, "datastore/$name")` gives `datastore`; `File(context.filesDir,
     * STAGING)` gives `staging`, resolving the constant in the same file.
     */
    private fun filesDirDirectories(): Set<String> {
        val found = sortedSetOf<String>()
        val unresolved = mutableListOf<String>()
        for (source in productionSources()) {
            val text = source.readText()
            for (match in FILES_DIR_CALL.findAll(text)) {
                val argument = match.groupValues[1].trim()
                val literal = literalOf(argument, text)
                if (literal == null) {
                    unresolved += "${RepoLayout.relative(source)}: File(filesDir, $argument)"
                    continue
                }
                literal.substringBefore('/').takeIf { it.isNotBlank() }?.let(found::add)
            }
        }
        assertTrue(unresolved.isEmpty()) {
            "Cannot resolve which folder these calls write into, so there is no way to say " +
                "whether they are excluded from backup:\n" + unresolved.joinToString("\n") { "  - $it" }
        }
        return found
    }

    /** An argument's value: a literal, or a `const val` declared in the same file. */
    private fun literalOf(argument: String, fileText: String): String? = when {
        argument.startsWith("\"") -> argument.trim('"')
        else -> Regex("""const val\s+${Regex.escape(argument)}\s*=\s*"([^"]+)"""")
            .find(fileText)?.groupValues?.get(1)
    }

    private fun productionSources(): List<File> = RepoLayout.walkSources()
        .filter { it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
        .toList()

    // --- reading the rules ----------------------------------------------------------------------

    private fun excludedFilePaths(ruleSet: RuleSet): List<String> = excluded(ruleSet, domain = "file")

    private fun excluded(ruleSet: RuleSet, domain: String): List<String> {
        val file = File(RepoLayout.root, ruleSet.path)
        assertTrue(file.isFile) { "Backup rules file missing: ${ruleSet.path}" }
        val document = RepoLayout.parseXml(file)
        val scope = if (ruleSet.section == null) {
            listOf(document.documentElement)
        } else {
            document.elements(ruleSet.section)
        }
        assertTrue(scope.isNotEmpty()) {
            "${ruleSet.path} is missing the section <${ruleSet.section}>."
        }
        return scope.flatMap { element ->
            val nodes = element.getElementsByTagName("exclude")
            (0 until nodes.length)
                .mapNotNull { nodes.item(it) as? org.w3c.dom.Element }
                .filter { it.getAttribute("domain") == domain }
                .map { it.getAttribute("path") }
        }
    }

    private data class RuleSet(val path: String, val section: String?) {
        fun describe(): String = if (section == null) path else "$path/<$section>"
    }

    private companion object {
        const val DATABASE_NAME = "multistore.db"

        /** `File(<something>.filesDir, <argument>)`, with or without an explicit receiver. */
        val FILES_DIR_CALL = Regex("""File\(\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)?filesDir\s*,\s*([^)]+)\)""")

        val RULE_SETS = listOf(
            RuleSet("app/src/main/res/xml/backup_rules.xml", section = null),
            RuleSet("app/src/main/res/xml/data_extraction_rules.xml", section = "cloud-backup"),
            RuleSet("app/src/main/res/xml/data_extraction_rules.xml", section = "device-transfer"),
        )
    }
}
