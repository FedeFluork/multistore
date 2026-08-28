plugins {
    alias(libs.plugins.multistore.store.adapter)
}

// :store:an1 — the adapter. The permitted dependencies are wired by the
// `multistore.store.adapter` convention plugin and verified by `checkDependencyRules`.
//
// The only addition: the shared contract test, which lives in `:store:api`'s test fixtures.
dependencies {
    testImplementation(testFixtures(projects.store.api))
}
