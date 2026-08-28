package com.multistore.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    applyPlugin("kotlin-compose")

    commonExtension.buildFeatures.compose = true

    addPlatform("implementation", "androidx-compose-bom")
    addPlatform("androidTestImplementation", "androidx-compose-bom")
    addPlatform("testImplementation", "androidx-compose-bom")

    addLib("implementation", "androidx-compose-ui")
    addLib("implementation", "androidx-compose-ui-graphics")
    addLib("implementation", "androidx-compose-ui-tooling-preview")
    addLib("implementation", "androidx-compose-foundation")
    addLib("implementation", "androidx-compose-material3")
    addLib("implementation", "androidx-compose-runtime")
    addLib("debugImplementation", "androidx-compose-ui-tooling")
    addLib("debugImplementation", "androidx-compose-ui-test-manifest")

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        // Stability reports land in build/compose-metrics: they explain why a composable
        // recomposes, without having to rebuild with ad-hoc flags.
        val dir = layout.buildDirectory.dir("compose-metrics")
        metricsDestination.set(dir)
        reportsDestination.set(dir)
    }
}
