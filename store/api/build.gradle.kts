plugins {
    alias(libs.plugins.multistore.jvm.library)
    `java-test-fixtures`
}

// :store:api — the StoreAdapter contract, the capabilities, StoreError, DownloadResolution.
// It is the only thing core and feature modules know about the stores.
//
// `java-test-fixtures` exists for one reason: `StoreAdapterContractTest`. Every adapter extends
// it, and a shared test belongs next to the contract it verifies — living in `:core:testing` the
// contract and its proof could diverge unnoticed. The `multistore.store.adapter` convention
// plugin adds the dependency to every adapter, so extending it is the shortest route rather than
// an obligation to remember.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(libs.kotlinx.coroutines.core)

    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.truth)
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesImplementation(projects.core.model)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
