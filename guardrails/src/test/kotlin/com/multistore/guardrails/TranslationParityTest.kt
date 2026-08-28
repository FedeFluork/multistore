package com.multistore.guardrails

import com.multistore.guardrails.RepoLayout.elements
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * Guardrail #2, rule 1: **no hardcoded strings, in any language** — and its operational consequence,
 * that every key exists *simultaneously* in all 5 languages.
 *
 * The test scans every `res` folder in the repository and compares `values/` (English, the default and
 * the fallback) with `values-it`, `values-fr`, `values-es`, `values-de`. It fails if:
 *
 *  - a key present in `values/` is missing from one of the 4 translations;
 *  - a translation contains a key that does not exist in `values/` (an orphan key: nearly always the
 *    leftover of a rename);
 *  - a `translatable="false"` string appears in a translation;
 *  - a `<plurals>` lacks one of the quantity forms CLDR requires for that language;
 *  - a `<string-array>` has a different number of elements between languages (the index carries meaning);
 *  - a translation has a different number of format placeholders from the original.
 *
 * Why a test and not just the lint: AGP's `MissingTranslation` looks at one module at a time and can be
 * disabled per resource; this looks at the whole repository and has no exemptions.
 */
@DisplayName("Translation parity across the 5 supported languages")
class TranslationParityTest {

    private companion object {
        /** `values/` is English: the compile-time default and the runtime fallback. */
        const val BASE_QUALIFIER = "values"

        /** The 4 mandatory translations. English is the base, not a translation. */
        val REQUIRED_QUALIFIERS = listOf("values-it", "values-fr", "values-es", "values-de")

        /**
         * Quantity forms CLDR requires for each language.
         *
         * `many` really does exist for it/fr/es (CLDR 42+, cases like "a million items") and the rule
         * asks for "every form the language requires", not only one/other.
         */
        val REQUIRED_PLURAL_QUANTITIES = mapOf(
            "values" to setOf("one", "other"),
            "values-it" to setOf("one", "many", "other"),
            "values-fr" to setOf("one", "many", "other"),
            "values-es" to setOf("one", "many", "other"),
            "values-de" to setOf("one", "other"),
        )

        /** `%s`, `%1$s`, `%d`, `%1$.2f`… — the placeholders must match between languages. */
        val FORMAT_PLACEHOLDER = Regex("""%(\d+\$)?[-+ #0,(]*\d*(\.\d+)?[a-zA-Z]""")
    }

    @Test
    @DisplayName("every values/ key exists in it, fr, es, de — and vice versa")
    fun translationsAreInParity() {
        val resourceDirs = RepoLayout.resourceDirectories()
        assertTrue(
            resourceDirs.isNotEmpty(),
            "No res/ folder with a strings.xml found: the test is looking at nothing, which " +
                "would be a false green.",
        )

        val problems = mutableListOf<String>()

        for (resDir in resourceDirs) {
            val baseFiles = RepoLayout.resourceFiles(resDir, BASE_QUALIFIER)
            val base = readResources(baseFiles, problems, RepoLayout.relative(resDir), BASE_QUALIFIER)

            // A res folder may contain only colours or themes: it is not a translation surface and there
            // is no sense in demanding it have any values-*/.
            val translationFiles = REQUIRED_QUALIFIERS
                .associateWith { RepoLayout.resourceFiles(resDir, it) }
            val hasAnyTranslatableResource = base.isNotEmpty() ||
                translationFiles.values.any { it.isNotEmpty() }
            if (!hasAnyTranslatableResource) continue

            if (base.isEmpty()) {
                problems += "${RepoLayout.relative(resDir)}: translations exist but " +
                    "$BASE_QUALIFIER/ defines no text resource at all, and it is the base as " +
                    "well as the runtime fallback."
                continue
            }

            problems += checkTranslatableFlags(resDir, base)
            problems += checkPluralQuantities(
                "${RepoLayout.relative(resDir)}/$BASE_QUALIFIER",
                BASE_QUALIFIER,
                base,
            )

            for (qualifier in REQUIRED_QUALIFIERS) {
                val location = "${RepoLayout.relative(resDir)}/$qualifier"
                val files = translationFiles.getValue(qualifier)
                if (files.isEmpty()) {
                    problems += "${RepoLayout.relative(resDir)}: $qualifier/ is missing entirely. " +
                        "A key is added to all 5 languages in the same commit."
                    continue
                }
                val translation = readResources(files, problems, RepoLayout.relative(resDir), qualifier)
                problems += compare(
                    location = location,
                    qualifier = qualifier,
                    base = base,
                    translation = translation,
                )
                problems += checkPluralQuantities(location, qualifier, translation)
            }
        }

        assertTrue(
            problems.isEmpty(),
            buildString {
                appendLine()
                appendLine("Translation parity violated (rule 1).")
                appendLine("A key must be added to ALL 5 languages in the same commit:")
                appendLine("  values/ (en) · values-it/ · values-fr/ · values-es/ · values-de/")
                appendLine()
                problems.sorted().forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Total problems: ${problems.size}")
            },
        )
    }

    // -------------------------------------------------------------- comparisons

    private fun compare(
        location: String,
        qualifier: String,
        base: Resources,
        translation: Resources,
    ): List<String> {
        val problems = mutableListOf<String>()

        val translatableBaseKeys = base.strings.filterValues { it.translatable }.keys
        val missing = translatableBaseKeys - translation.strings.keys
        missing.sorted().forEach {
            problems += "$location: missing the key <string name=\"$it\"> present in values/."
        }

        val orphan = translation.strings.keys - base.strings.keys
        orphan.sorted().forEach {
            problems += "$location: the key <string name=\"$it\"> does not exist in values/ " +
                "(orphan: likely the leftover of a rename)."
        }

        val untranslatablePresent = translation.strings.keys.filter {
            base.strings[it]?.translatable == false
        }
        untranslatablePresent.sorted().forEach {
            problems += "$location: <string name=\"$it\"> is translatable=\"false\" in values/ " +
                "and must not appear in the translations."
        }

        val missingPlurals = base.plurals.keys - translation.plurals.keys
        missingPlurals.sorted().forEach {
            problems += "$location: missing <plurals name=\"$it\"> present in values/."
        }
        val orphanPlurals = translation.plurals.keys - base.plurals.keys
        orphanPlurals.sorted().forEach {
            problems += "$location: <plurals name=\"$it\"> does not exist in values/ (orphan)."
        }

        val missingArrays = base.arrays.keys - translation.arrays.keys
        missingArrays.sorted().forEach {
            problems += "$location: missing <string-array name=\"$it\"> present in values/."
        }
        val orphanArrays = translation.arrays.keys - base.arrays.keys
        orphanArrays.sorted().forEach {
            problems += "$location: <string-array name=\"$it\"> does not exist in values/ (orphan)."
        }
        base.arrays.forEach { (name, baseItems) ->
            val translated = translation.arrays[name] ?: return@forEach
            if (translated != baseItems) {
                problems += "$location: <string-array name=\"$name\"> has $translated items " +
                    "against values/'s $baseItems. The array index carries meaning in the code."
            }
        }

        // The format placeholders must match, otherwise the app crashes only in the wrong language — the
        // kind of bug nobody sees in development.
        base.strings.forEach { (name, baseString) ->
            if (!baseString.translatable) return@forEach
            val translated = translation.strings[name] ?: return@forEach
            val basePlaceholders = placeholdersOf(baseString.value)
            val translatedPlaceholders = placeholdersOf(translated.value)
            if (basePlaceholders != translatedPlaceholders) {
                problems += "$location: <string name=\"$name\"> has placeholders " +
                    "$translatedPlaceholders against values/'s $basePlaceholders."
            }
        }

        require(qualifier.isNotEmpty())
        return problems
    }

    private fun checkTranslatableFlags(resDir: File, base: Resources): List<String> {
        // A translatable="false" key that is never used as such is nearly always a typo: we report it
        // only if it is also empty.
        return base.strings
            .filter { (_, value) -> !value.translatable && value.value.isBlank() }
            .keys
            .sorted()
            .map {
                "${RepoLayout.relative(resDir)}: <string name=\"$it\"> is translatable=\"false\" " +
                    "but empty."
            }
    }

    private fun checkPluralQuantities(
        location: String,
        qualifier: String,
        resources: Resources,
    ): List<String> {
        val required = REQUIRED_PLURAL_QUANTITIES[qualifier] ?: return emptyList()
        return resources.plurals.flatMap { (name, quantities) ->
            (required - quantities).sorted().map { missing ->
                "$location: <plurals name=\"$name\"> lacks the form <item quantity=\"$missing\">, " +
                    "required by CLDR for $qualifier (present: ${quantities.sorted()})."
            }
        }
    }

    private fun placeholdersOf(value: String): List<String> =
        FORMAT_PLACEHOLDER.findAll(value).map { it.value }.toList().sorted()

    // ---------------------------------------------------------------- parsing

    private data class StringResource(val value: String, val translatable: Boolean)

    private data class Resources(
        /** name -> value + translatable flag */
        val strings: Map<String, StringResource>,
        /** name -> the set of declared quantities */
        val plurals: Map<String, Set<String>>,
        /** name -> number of <item> */
        val arrays: Map<String, Int>,
    ) {
        fun isEmpty(): Boolean = strings.isEmpty() && plurals.isEmpty() && arrays.isEmpty()
        fun isNotEmpty(): Boolean = !isEmpty()
    }

    /**
     * Reads and **merges** all the text resources of one qualifier.
     *
     * Android treats a whole `values*` folder as a single resource set: `strings.xml`, `plurals.xml` and
     * `strings_search.xml` end up in the same namespace. The guardrail has to see the same thing,
     * otherwise moving a key into a second file is enough to make it invisible to the parity check.
     *
     * A key defined twice in the same qualifier is an error: at runtime the last would win, in an order
     * we do not control.
     */
    private fun readResources(
        files: List<File>,
        problems: MutableList<String>,
        resDirLabel: String,
        qualifier: String,
    ): Resources {
        val strings = mutableMapOf<String, StringResource>()
        val plurals = mutableMapOf<String, Set<String>>()
        val arrays = mutableMapOf<String, Int>()
        val origin = mutableMapOf<String, String>()

        fun <T> put(target: MutableMap<String, T>, name: String, value: T, file: File, kind: String) {
            val previous = origin.put("$kind:$name", file.name)
            if (previous != null) {
                problems += "$resDirLabel/$qualifier: <$kind name=\"$name\"> is defined twice, " +
                    "in $previous and in ${file.name}."
            }
            target[name] = value
        }

        for (file in files) {
            val document = RepoLayout.parseXml(file)

            document.elements("string")
                .filter { it.parentNode?.nodeName == "resources" }
                .forEach { element ->
                    val name = element.getAttribute("name")
                    put(
                        strings,
                        name,
                        StringResource(
                            value = element.textContent.orEmpty(),
                            translatable = element.getAttribute("translatable") != "false",
                        ),
                        file,
                        "string",
                    )
                }

            document.elements("plurals").forEach { element ->
                val name = element.getAttribute("name")
                val quantities = element.childElements("item")
                    .map { it.getAttribute("quantity") }
                    .toSet()
                put(plurals, name, quantities, file, "plurals")
            }

            document.elements("string-array").forEach { element ->
                val name = element.getAttribute("name")
                put(arrays, name, element.childElements("item").size, file, "string-array")
            }
        }

        return Resources(strings, plurals, arrays)
    }

    private fun Element.childElements(tag: String): List<Element> =
        (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) as? Element }
            .filter { it.nodeName == tag }
}
