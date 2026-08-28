// Root build. Plugins are declared here with `apply false` so they land on the build
// classpath; the convention plugins in `build-logic/` then apply them by id.
//
// AGP 9 note: `org.jetbrains.kotlin.android` does not appear here and must not — Kotlin
// support is built into the Android Gradle Plugin. See gradle.properties.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.roborazzi) apply false
}
