plugins {
    alias(libs.plugins.multistore.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// :store:common — helpers shared by the adapters: error translation, snippet hashing for
// diagnostics, the wrapper that guarantees no exception leaves an adapter, and the HTML/XML
// reading layer that refuses to produce a silently empty field.
dependencies {
    api(projects.store.api)
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.network)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
