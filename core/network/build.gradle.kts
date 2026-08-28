plugins {
    alias(libs.plugins.multistore.jvm.library)
    alias(libs.plugins.multistore.jvm.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// :core:network — OkHttp, interceptors (UA, cache, rate limit, retry), Jsoup helpers and
// rungs 0-1 of the ChallengeResolver ladder (PlainResolver, ProtocolFallbackResolver): both
// are pure network negotiation, so they live here, in pure Kotlin.
//
// Rungs 2-4 need Android and live elsewhere: rung 3 (silent WebView) in `:core:challenge`,
// rung 4 (visible WebView, the user's tap) in `:feature:webviewdownload`. Rung 2 (Cronet)
// does not exist: no measurement justifies it and `cronet-embedded` weighs ~8 MB.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
