plugins {
    alias(libs.plugins.multistore.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// :core:model — pure data classes. No Android dependency and no dependency on another module
// of the project: it is the innermost node of the graph.
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.collections.immutable)
}
