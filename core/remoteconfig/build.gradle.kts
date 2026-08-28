plugins {
    alias(libs.plugins.multistore.android.library)
    alias(libs.plugins.multistore.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.multistore.core.remoteconfig"

    // BouncyCastle's R8 rules travel with the module that depends on BouncyCastle, not with :app.
    // Same reason as :core:installer with apksig.
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

// :core:remoteconfig — fetching and Ed25519 verification of parsers.json and index.json.
// A missing or invalid signature => the document is discarded and the compiled defaults are used.
dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.network)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
