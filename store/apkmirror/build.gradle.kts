plugins {
    alias(libs.plugins.multistore.store.adapter)
}

// :store:apkmirror — the adapter. As with apkcombo, no dependency beyond what the
// `multistore.store.adapter` convention plugin already wires: what makes this store special is
// entirely in its network profile and its selectors, not in the classpath.
dependencies {
    testImplementation(testFixtures(projects.store.api))
}
