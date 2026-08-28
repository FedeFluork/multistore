plugins {
    alias(libs.plugins.multistore.android.library.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.multistore.core.testing"
}

// :core:testing — shared test infrastructure.
//
// It exists for a precise reason: the project requires a Roborazzi screenshot test in *both* themes
// for every screen. Without a common place, that logic would end up copied into seven feature
// modules, and seven copies diverge. Here it lives once.
//
// The module is consumed only via `testImplementation`: it does not end up in the APK.
dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)
    // `:store:api` and not a concrete store: `FakeStoreAdapter` lives here, and it serves to build a
    // `StoreRegistry` in a test without dragging the network along. `checkDependencyRules`'s rule R1
    // forbids `:core:*` the **concrete** stores, not the contract.
    api(projects.store.api)
    // The repositories' test doubles live here: the three ViewModels of the critical path all ask for
    // them, and six copies of the same fake diverge. Only `testImplementation` consumes this module,
    // so `:core:data` does not enter the APK this way.
    api(projects.core.data)

    api(libs.junit4)
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.roborazzi.junit.rule)
    api(libs.roborazzi.accessibility.check)
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.androidx.test.core)
    api(libs.androidx.test.ext.junit)
    api(libs.truth)
    api(libs.turbine)
    api(libs.kotlinx.coroutines.test)
}
