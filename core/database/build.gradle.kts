plugins {
    alias(libs.plugins.multistore.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.multistore.android.hilt)
    alias(libs.plugins.multistore.android.room)
}

android {
    namespace = "com.multistore.core.database"
}

/**
 * The exported schemas are an **input** to the tests, not merely an artifact to commit.
 *
 * `MigrationTest` rebuilds the previous database version from the committed schema, not from a
 * hand-copied list of `CREATE TABLE`s: a hand-written list is written once and then lies, because
 * nobody realigns it when an entity changes. With the real schema as the source, the migration is
 * tested against what users actually have on their phones.
 *
 * The path arrives as a system property rather than from a source set: in AGP 9 the
 * `android.sourceSets` container is no longer castable to the type the Kotlin DSL expects, and
 * configuration fails before compiling. A property is also a `String`, hence compatible with the
 * configuration cache. `inputs.dir` closes the loop: changing a schema makes the tests out of
 * date.
 */
tasks.withType<Test>().configureEach {
    val schemas = layout.projectDirectory.dir("schemas")
    systemProperty("multistore.schemaDir", schemas.asFile.absolutePath)
    inputs.dir(schemas).withPropertyName("roomSchemas").withPathSensitivity(PathSensitivity.RELATIVE)
}

// :core:database — Room: entities, DAOs, migrations. Versioned schemas live in schemas/.
dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    // `api` and not `implementation`: two DAOs return a `PagingSource`, so that type is part of
    // `CatalogDao`'s contract and consumers must be able to name it.
    api(libs.androidx.room.paging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
