plugins {
    alias(libs.plugins.multistore.android.library)
    alias(libs.plugins.multistore.android.hilt)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.multistore.core.datastore"
}

// :core:datastore — the settings Proto DataStore.
// Rule 2: every configurable feature has a field in settings.proto, an entry in the Settings
// screen and 5 translations. SettingsCoverageTest checks it.
protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") { option("lite") }
                register("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.kotlinx.coroutines.android)

    // The module's only logic is the translation between proto and domain. One part of it
    // earns tests on its own: **which behaviour sits at the zero value**.
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
