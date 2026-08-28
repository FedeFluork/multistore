plugins {
    alias(libs.plugins.multistore.android.library)
    alias(libs.plugins.multistore.android.hilt)
}

android {
    namespace = "com.multistore.core.updates"
}

// :core:updates — the periodic update check: worker, scheduling, notification.
//
// A module of its own for the same reason as `:core:download`, and not for symmetry: WorkManager, a
// notification channel and the POST_NOTIFICATIONS permission belong to whoever uses them. In `:app`
// they would end up among everything's dependencies; in `:core:data` they would bring the worker
// engine inside the repositories' module.
//
// Unlike `:core:download`, this module **can** see the repositories: it depends on `:core:domain`,
// which exposes them. So no inverted interface like `DownloadTask` is needed — that was needed there
// because `:core:data` depends on `:core:download` and the arrow cannot be turned round.
dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    // The repositories' doubles live in `:core:testing`, which depends on `:core:data`. No cycle:
    // `:core:testing` does not know this module. It is the same route the features use.
    //
    // The BOM has to be added by hand because this module is **not** a Compose module: `:core:testing`
    // also brings the screenshot tests' base along, and those dependencies take their version from the
    // BOM the Compose convention plugin applies. Without it, resolution fails with "Could not find
    // androidx.compose.ui:ui-test-junit4:" — i.e. with the empty version.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(projects.core.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
}
