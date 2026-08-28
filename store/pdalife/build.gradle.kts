plugins {
    alias(libs.plugins.multistore.store.adapter)
}

// :store:pdalife — adapter. The allowed dependencies are wired by the `multistore.store.adapter`
// convention plugin and checked by `checkDependencyRules`.
//
// The only addition: the shared contract test, which lives in `:store:api`'s `testFixtures`.
dependencies {
    testImplementation(testFixtures(projects.store.api))
}
