plugins {
    alias(libs.plugins.multistore.android.library.compose)
}

android {
    namespace = "com.multistore.core.designsystem"
}

// :core:designsystem — theme (light/dark/system + dynamic colour) and tokens.
// Rule 3: colours come ONLY from here or from MaterialTheme.colorScheme.
// This is the only module in the project where writing a Color(0xFF...) is allowed.
dependencies {
    api(projects.core.model)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material3.windowsize)
    // api: icons are part of the visual vocabulary, not a design-system implementation detail.
    api(libs.androidx.compose.material.icons.extended)
}
