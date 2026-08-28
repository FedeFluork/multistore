plugins {
    alias(libs.plugins.multistore.jvm.library)
    alias(libs.plugins.multistore.jvm.hilt)
}

// :core:common — Result/AppError, dispatchers, RateLimiter, CircuitBreaker, text
// normalisation, the version-selection rule. Pure Kotlin: it lives without Android and is
// tested without Robolectric.
dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
}
