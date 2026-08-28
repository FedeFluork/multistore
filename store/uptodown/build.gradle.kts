plugins {
    alias(libs.plugins.multistore.store.adapter)
}

// :store:uptodown — adapter. The allowed dependencies are wired by the `multistore.store.adapter`
// convention plugin and checked by `checkDependencyRules`.
dependencies {
    testImplementation(testFixtures(projects.store.api))
}
