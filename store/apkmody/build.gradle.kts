plugins {
    alias(libs.plugins.multistore.store.adapter)
}

// :store:apkmody — the adapter. Permitted dependencies are wired by the
// `multistore.store.adapter` convention plugin and verified by `checkDependencyRules`.
//
// Nothing beyond the contract test: apkmody is plain HTML, and the convention plugin already
// brings Jsoup.
dependencies {
    testImplementation(testFixtures(projects.store.api))
}
