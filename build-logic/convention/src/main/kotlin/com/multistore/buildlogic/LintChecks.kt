package com.multistore.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Path of the module that hosts the project's custom lint checks. */
private const val LINT_RULES_PROJECT = ":lint-rules"

/**
 * Wires the custom lint checks into every module, except the module that defines them (it
 * would depend on itself) and modules without a `lintChecks` configuration.
 */
internal fun Project.wireCustomLintChecks() {
    if (path == LINT_RULES_PROJECT) return
    if (configurations.findByName("lintChecks") == null) return
    dependencies {
        add("lintChecks", project(LINT_RULES_PROJECT))
    }
}
