plugins {
    alias(libs.plugins.multistore.android.feature)
}

android {
    namespace = "com.multistore.feature.storelisting"
}

// :feature:storelisting — the dependencies common to every feature are wired by the
// `multistore.android.feature` convention plugin. A feature NEVER depends on another feature nor on
// a concrete `:store:<name>`: `checkDependencyRules` verifies that.
dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.data)
    // An index store's catalogue is thousands of rows: it is the only screen in the app where Paging 3
    // genuinely earns its keep. See `SearchRepository.browsePaged`.
    implementation(libs.androidx.paging.compose)
    testImplementation(libs.androidx.paging.testing)
}
