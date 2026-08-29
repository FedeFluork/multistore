plugins {
    alias(libs.plugins.multistore.android.feature)
}

android {
    namespace = "com.multistore.feature.downloads"
}

// :feature:downloads — the transfers, what is waiting to be installed, and what has already
// happened. The dependencies common to every feature are wired by the `multistore.android.feature`
// convention plugin. A feature NEVER depends on another feature nor on a concrete `:store:<name>`:
// `checkDependencyRules` verifies that.
dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.data)
}
