package com.multistore.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * The Android + Kotlin configuration shared by every Android module.
 *
 * AGP 9 note: Kotlin support is *built into* the Android Gradle Plugin. The
 * `org.jetbrains.kotlin.android` plugin must NOT be applied (it fails with an explicit error),
 * but the `kotlin { }` extension is still available and is a [KotlinAndroidProjectExtension].
 * `kotlin.sourceSets` cannot be used to add sources, though: use `android.sourceSets`.
 */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.compileSdk = libs.intVersion("compileSdk")
    commonExtension.buildToolsVersion = libs.version("buildTools")

    commonExtension.defaultConfig.apply {
        minSdk = libs.intVersion("minSdk")
    }

    val target = jvmTargetVersion()
    commonExtension.compileOptions.apply {
        sourceCompatibility = JavaVersion.toVersion(target)
        targetCompatibility = JavaVersion.toVersion(target)
    }

    commonExtension.testOptions.apply {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    commonExtension.packaging.resources.apply {
        excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        excludes.add("/META-INF/LICENSE*")
        excludes.add("/META-INF/DEPENDENCIES")
        excludes.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(target))
            freeCompilerArgs.addAll(COMMON_COMPILER_ARGS)
        }
    }

    // Gradle 9 fails a test task that discovers no tests. On a multi-module project that
    // signal is noisy and false: a module with no tests still receives generated sources
    // (proto, R, Hilt) in its test source set, so it looks like it has test sources. Real
    // coverage is watched by CI, not by this flag.
    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
        failOnNoDiscoveredTests.set(false)
    }

    configureLint(commonExtension)
}

internal val COMMON_COMPILER_ARGS = listOf(
    "-Xconsistent-data-class-copy-visibility",
    "-opt-in=kotlin.RequiresOptIn",
)
