plugins {
    alias(libs.plugins.multistore.store.adapter)
}

// :store:fdroid — adapter. The allowed dependencies are wired by the `multistore.store.adapter`
// convention plugin and checked by `checkDependencyRules`.
//
// The only addition is Moshi, and for a single class: `JsonReader`. The index weighs 57 MB and has
// to be walked without building its tree. The reasoning is in the version catalog's comment.
dependencies {
    implementation(libs.moshi)
    testImplementation(testFixtures(projects.store.api))
}
