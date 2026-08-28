plugins {
    alias(libs.plugins.multistore.android.library)
    alias(libs.plugins.multistore.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.multistore.core.installer"

    // `apksig`'s R8 rule travels with the module that depends on `apksig`, not with :app: whoever
    // adds this module elsewhere carries it along without having to know.
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

// :core:installer — Installer (Session/Shizuku/Root) and the pre-install verification pipeline.
dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.apksig)

    // Needed to read a container's metadata: an XAPK's `manifest.json`, an APKM's `info.json`.
    // They are third-party documents with keys we do not all know, so they are read as `JsonObject`
    // and not with `@Serializable`: one extra field must not make reading the ones we need fail.
    implementation(libs.kotlinx.serialization.json)

    // `implementation` and no longer `compileOnly`: while Shizuku was a placeholder, compiling
    // against it was enough, but `ShizukuShell` really uses it — and `ShizukuProvider`, declared in
    // this module's manifest, is what hands the binder to the app. A `compileOnly` would give
    // NoClassDefFoundError on the first availability check.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
