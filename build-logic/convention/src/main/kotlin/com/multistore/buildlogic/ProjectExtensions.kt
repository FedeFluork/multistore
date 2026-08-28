package com.multistore.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The shared version catalog. No module ever declares a dependency version inline. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow {
        IllegalStateException("Version '$alias' missing from gradle/libs.versions.toml")
    }.requiredVersion

internal fun VersionCatalog.intVersion(alias: String): Int = version(alias).toInt()

/** Adds a catalog library to a configuration, by alias. */
internal fun Project.addLib(configuration: String, alias: String) {
    dependencies.add(
        configuration,
        libs.findLibrary(alias).orElseThrow {
            IllegalStateException("Library '$alias' missing from gradle/libs.versions.toml")
        }.get(),
    )
}

/** Adds a catalog platform (BOM) to a configuration, by alias. */
internal fun Project.addPlatform(configuration: String, alias: String) {
    dependencies.add(
        configuration,
        dependencies.platform(
            libs.findLibrary(alias).orElseThrow {
                IllegalStateException("BOM '$alias' missing from gradle/libs.versions.toml")
            }.get(),
        ),
    )
}

/** Applies a plugin identified in the catalog by alias. */
internal fun Project.applyPlugin(alias: String) {
    pluginManager.apply(
        libs.findPlugin(alias).orElseThrow {
            IllegalStateException("Plugin '$alias' missing from gradle/libs.versions.toml")
        }.get().pluginId,
    )
}

/** The project's bytecode target, read from the catalog: one source for Kotlin and Java. */
internal fun Project.jvmTargetVersion(): String = libs.version("jvmTarget")
