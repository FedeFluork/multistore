plugins {
    alias(libs.plugins.multistore.android.library)
    alias(libs.plugins.multistore.android.hilt)
}

android {
    namespace = "com.multistore.core.domain"
}

// :core:domain — use cases. No knowledge of any concrete store: only :store:api.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.data)
    api(projects.store.api)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
