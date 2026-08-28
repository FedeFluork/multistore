package com.multistore.guardrails

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guardrail #4, rule 3: **every UI component works in light and dark**.
 *
 * "Every new screen has a Roborazzi screenshot test in both themes. `ScreenshotCoverageTest` fails if
 * a screen has only one."
 *
 * The test applies the project's convention: a public composable `<Name>Screen` in a `:feature:*`
 * module must have
 *
 *  1. a `<Name>ScreenScreenshotTest` test capturing both `ThemeMode.LIGHT` and `ThemeMode.DARK`;
 *  2. the two goldens already recorded, `<Name>Screen_light.png` and `<Name>Screen_dark.png`.
 *
 * Point 2 is what makes the guardrail useful rather than ceremonial: a test that captures without a
 * baseline compares nothing. If they are missing, the fix is one line: `./gradlew recordRoborazziDebug`.
 */
@DisplayName("Screenshot coverage: every screen in light and dark")
class ScreenshotCoverageTest {

    private companion object {
        /**
         * A screen composable: `@Composable ... fun NameScreen(`.
         *
         * Previews do not get caught because the name must end exactly with `Screen` immediately
         * before the parenthesis: `HomeScreenLightPreview(` does not match.
         */
        val SCREEN_COMPOSABLE = Regex(
            """@Composable[\s\S]{0,200}?\bfun\s+([A-Z]\w*Screen)\s*\(""",
        )

        const val LIGHT_MARKER = "ThemeMode.LIGHT"
        const val DARK_MARKER = "ThemeMode.DARK"
    }

    @Test
    @DisplayName("every <Name>Screen of a feature has a light and a dark screenshot")
    fun everyScreenHasBothThemes() {
        val featureModules = File(RepoLayout.root, "feature")
            .listFiles { file -> file.isDirectory && File(file, "build.gradle.kts").isFile }
            ?.sortedBy { it.name }
            .orEmpty()

        assertTrue(
            featureModules.isNotEmpty(),
            "No :feature:* module found: the test is looking at nothing.",
        )

        val problems = mutableListOf<String>()
        var screensChecked = 0

        for (module in featureModules) {
            val mainSources = File(module, "src/main/kotlin")
            if (!mainSources.isDirectory) continue

            val screens = mainSources.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> SCREEN_COMPOSABLE.findAll(file.readText()).map { it.groupValues[1] } }
                .distinct()
                .sorted()
                .toList()

            for (screen in screens) {
                screensChecked++
                problems += checkScreen(module, screen)
            }
        }

        assertTrue(
            screensChecked > 0,
            "No `<Name>Screen` composable found in the feature modules. Either the naming " +
                "convention has changed, or this guardrail is passing vacuously: in both cases " +
                "it needs fixing, not ignoring.",
        )

        assertTrue(
            problems.isEmpty(),
            buildString {
                appendLine()
                appendLine("Screenshot coverage violated (rule 3).")
                appendLine("Screens checked: $screensChecked")
                appendLine()
                problems.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("To record the missing goldens: ./gradlew recordRoborazziDebug")
            },
        )
    }

    private fun checkScreen(module: File, screen: String): List<String> {
        val problems = mutableListOf<String>()
        val moduleLabel = "feature/${module.name}"

        val testSources = File(module, "src/test/kotlin")
        val testFile = testSources.takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.firstOrNull { it.isFile && it.name == "${screen}ScreenshotTest.kt" }

        if (testFile == null) {
            problems += "$moduleLabel: `$screen` has no `${screen}ScreenshotTest.kt`. " +
                "Extend :core:testing's `ScreenshotTest` and capture both themes."
            return problems
        }

        val testBody = testFile.readText()
        if (!testBody.contains(LIGHT_MARKER)) {
            problems += "$moduleLabel: `${screen}ScreenshotTest` does not capture $LIGHT_MARKER."
        }
        if (!testBody.contains(DARK_MARKER)) {
            problems += "$moduleLabel: `${screen}ScreenshotTest` does not capture $DARK_MARKER."
        }

        val screenshotDir = File(module, "src/test/screenshots")
        listOf("light", "dark").forEach { theme ->
            val golden = File(screenshotDir, "${screen}_$theme.png")
            if (!golden.isFile) {
                problems += "$moduleLabel: the golden `${screen}_$theme.png` is missing. " +
                    "Record it with `./gradlew recordRoborazziDebug` and commit it: without a " +
                    "baseline the test captures but compares nothing."
            }
        }

        return problems
    }
}
