plugins {
    alias(libs.plugins.multistore.store.adapter)
}

// :store:apkcombo — the adapter. Permitted dependencies are wired by the
// `multistore.store.adapter` convention plugin and verified by `checkDependencyRules`.
//
// Nothing added: apkcombo is plain HTML, and the convention plugin already brings Jsoup. It is
// the first adapter to demonstrate that adding a store requires no change to the core — this
// file, which contains nothing special, is part of the demonstration.
dependencies {
    testImplementation(testFixtures(projects.store.api))
}
