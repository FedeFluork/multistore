plugins {
    alias(libs.plugins.multistore.android.library.compose)
    // Roborazzi, and with it the accessibility check hooked to `ScreenshotTest.capture`. Rule 3
    // speaks of "every UI component", not only of every screen: the downloads progress card lives
    // here and is drawn by `:app` above everything, so there is no `:feature:*` that could
    // photograph it.
    alias(libs.plugins.multistore.android.screenshot)
}

android {
    namespace = "com.multistore.core.ui"
}

// :core:ui — shared components that know the model (AppCard, StoreChip, store status banners…).
// What two features share lives here, not in a "sibling" feature.
dependencies {
    api(projects.core.designsystem)
    implementation(projects.core.model)
    // The errors' vocabulary (`AppError`) is shared: the same sentence has to say the same thing in
    // the search, on the Home and on the detail screen.
    implementation(projects.core.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // :core:ui gained its first logic of its own: the translation of `PackageInstaller`'s codes into
    // seven different diagnoses. It is a `when` over framework constants, i.e. exactly the kind of
    // thing that goes wrong silently.
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
