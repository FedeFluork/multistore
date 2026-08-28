plugins {
    alias(libs.plugins.multistore.android.feature)
}

android {
    namespace = "com.multistore.feature.webviewdownload"
}

// :feature:webviewdownload — the dependencies common to every feature are wired by the
// `multistore.android.feature` convention plugin. A feature NEVER depends on another feature nor on
// a concrete `:store:<name>`: `checkDependencyRules` verifies that.
dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(libs.androidx.webkit)
    // The back button must return to the page's previous hop instead of leaving the screen — an assisted
    // download crosses three or four hops, and leaving on the first tap would force redoing everything,
    // captcha included. That needs `BackHandler`.
    implementation(libs.androidx.activity.compose)
}
