plugins {
    alias(libs.plugins.multistore.android.library)
    alias(libs.plugins.multistore.android.hilt)
}

android {
    namespace = "com.multistore.core.challenge"
}

// :core:challenge — the escalation ladder's rungs that need Android.
//
// It exists as a module of its own for the simplest possible reason: `:core:network` is pure Kotlin
// — `checkDependencyRules` verifies it, and it exists so it can be tested on the JVM without
// Robolectric — whereas a WebView is a system View. Rungs 0 and 1 are network negotiation and stay
// there; from 2 up a browser engine is needed, and therefore a place where Android is allowed.
//
// **Cronet (rung 2) is absent, and that is not an oversight.** No measurement shows a store passing
// with Cronet and failing with OkHttp; `cronet-embedded` weighs about 8 MB. `ChallengeResolver`
// remains an open interface: adding it one day touches no adapter.
dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.network)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
