package com.multistore.buildlogic

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.Project

/**
 * Rule 1 — no hardcoded user-visible strings — in executable form.
 *
 * `HardcodedText` only covers layout XML, which in an all-Compose app is a guardrail without
 * teeth. Hence the custom check in `:lint-rules` ([ISSUE_COMPOSE_HARDCODED_TEXT]), which
 * catches string literals passed to text composables. Both are raised to `error`.
 */
internal const val ISSUE_COMPOSE_HARDCODED_TEXT = "MultiStoreComposeHardcodedText"

private val ERROR_LEVEL_ISSUES = setOf(
    "HardcodedText",
    ISSUE_COMPOSE_HARDCODED_TEXT,
    "MissingTranslation",
    "ExtraTranslation",
    "ImpliedQuantity",
    "StringFormatInvalid",
    "StringFormatCount",
)

internal fun Project.configureLint(commonExtension: CommonExtension) =
    commonExtension.lint.applyMultiStoreLintPolicy(this)

internal fun Lint.applyMultiStoreLintPolicy(project: Project) {
    abortOnError = true
    checkDependencies = true
    /**
     * Lint looks at the code that ships in the APK, not at test code.
     *
     * The two checks raised to error here concern **user-visible** strings, and a Roborazzi
     * golden is deliberately made of dummy text written in the test.
     *
     * There is a practical reason too: on a navigation test with `@Serializable` routes and
     * `createGraph`, analysing test sources fails with an internal lint error ("Error while
     * resolving … from RAW_FIR to COMPILER_REQUIRED_ANNOTATIONS"). A build that fails on a
     * tool defect, over files the tool need not look at, is the worst of both worlds.
     */
    ignoreTestSources = true
    checkReleaseBuilds = true
    warningsAsErrors = false
    explainIssues = true
    error += ERROR_LEVEL_ISSUES
    // Noise without value here: MultiStore is not distributed on the Play Store.
    disable += setOf(
        "GoogleAppIndexingWarning",
        "ObsoleteLintCustomCheck",
    )
    project.rootProject.file("config/lint/lint.xml").takeIf { it.exists() }?.let {
        lintConfig = it
    }
}
